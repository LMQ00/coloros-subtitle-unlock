package com.lmq.coloros.subtitle;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块：解除 ColorOS 16 系统 AI 音频功能的客户端限制。
 *
 * 1) 「AI 语音摘记」(com.coloros.accessibilityassistant) 开启字幕的每月 120 分钟限制。
 *    逆向结论（APK 16.3.12）：
 *      云端 ASR 通过 AIUnit 返回错误码 3000803（"月额度已达限"），
 *      经 com.coloros.translate.engine.asr.asrclient.h#e 映射为 e4.c.ASR_MONTHLY_LIMIT_REACHED(-2020)，
 *      再由引擎分发器 com.coloros.translate.engine.asr.s#onResultStatus 转发给各 WorkManager，
 *      最终 GlobalSubtitleWorkManager 停止字幕并弹「已达上限」提示。
 *    本模块在三个层面丢弃这些限制状态码，并把 UI 的「本月剩余时长」改写为极大值。
 *    注意：配额由云端/系统 AIUnit 判定，本模块只解除客户端对限制的反应。
 *
 * 2) 「声音分轨」(com.oplus.smartmediacontroller) 仅限名单内 App 使用。
 *    逆向结论（APK 16.1.20 + 系统库反汇编）：
 *      判定在 native 服务 android::SpecailizerPLService（跑在 atlasservice 进程）：
 *      setMssEnable(pkg,true) 先调 isVocalAdjustSupported(pkg)，该函数查白名单
 *      "mss-whitelist"：不在名单内直接拒绝；名单内 attribute 的 bit4 置位（如 bilibili 的 17）
 *      时还要看 isMssMusicOnly()，为真则拒绝 —— App 收到非 0 便弹「当前应用暂不支持声音分轨」。
 *
 *      **不要动 mss_music_only 参数**：把它压成 0 虽能让名单外 App 也开面板，但音频策略
 *      （AudioPolicyManagerExtImpl::shouldNotForceMss / shouldForceMssBySession）会因此不再为
 *      该 App 强制 MSS 通路，表现为「面板可用、拖滑块无听感变化」。
 *      本模块改为**只扩名单**：以内置白名单为底原样保留全部条目，仅追加缺失的包（attribute=3），
 *      写在线白名单并让 mmlistservice 重读。详见 startWhitelistUnlock()。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "ColorOSSubtitleUnlock";
    private static final String TARGET_PKG = "com.coloros.accessibilityassistant";
    /** System Framework（system_server）：只有它能写白名单文件、并让 init 重启 mmlistservice。 */
    private static final String TARGET_PKG_SYSTEM = "android";

    /** 内置分轨白名单（只读，version 最高时为生效源）。 */
    private static final String BUILTIN_LIST_PATH = "/system_ext/etc/Multimedia_Daemon_List.xml";
    /** 可写的「在线更新」白名单；version 高于内置时生效。 */
    private static final String ONLINE_LIST_PATH = "/data/oplus/multimedia/Multimedia_Daemon_Online_List.xml";
    /** 写入的 version，必须高于内置文件的 version。 */
    private static final String LIST_VERSION = "20991231";
    /** 追加条目的 attribute：bit0 置位（支持人声调节）、bit4 清零（不受「仅音乐」判定限制）。 */
    private static final String LIST_ATTRIBUTE = "3";
    /** 解析白名单条目的原生进程，写文件后需重启它才会重读。 */
    private static final String SERVICE_MMLISTSERVICE = "mmlistservice";

    // t3.a / e4.c 状态码
    private static final int CODE_USE_TIME_TOO_LONG = -2017;
    private static final int CODE_USE_TIME_LIMIT_REACHED = -2018;
    private static final int CODE_MONTHLY_LIMIT_REACHED = -2020;

    // AIUnit 云端原始错误码
    private static final int RAW_USE_TIME_TOO_LONG = 3000801;
    private static final int RAW_USE_TIME_LIMIT_REACHED = 3000802;
    private static final int RAW_MONTHLY_LIMIT_REACHED = 3000803;

    private static final long UNLIMITED_DURATION = 999_999_999L;

    /** 匹配 `<name>` 及其后紧邻的 `<attribute>`（只在 `<mss-whitelist>` 段内使用）。 */
    private static final Pattern ENTRY = Pattern.compile(
            "<name>\\s*([^<]+?)\\s*</name>(\\s*)(<attribute>\\s*(\\d+)\\s*</attribute>)?");

    /** 字幕侧配置（UI 写、这里只读；null = 尚未装载）。 */
    private static volatile ConfigReader sSubtitleConfig;

    /** 分轨侧配置。 */
    private static volatile ConfigReader sConfig;

    /** 配置变更 → 唤醒分轨工作线程。 */
    private static final Object CONFIG_LOCK = new Object();
    private static boolean configDirty = true;
    /** 「prefs 不可读」只打一次，避免每 5 秒重试都刷日志。 */
    private static boolean prefsMissingLogged;

    /** 字幕开关；配置未装载或不可读时按默认值（解锁）处理。 */
    private static boolean subtitleUnlocked() {
        ConfigReader c = sSubtitleConfig;
        return c == null || !c.isAvailable() || c.subtitleUnlock();
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (TARGET_PKG_SYSTEM.equals(lp.packageName)) {
            log("module loading in system_server (System Framework)");
            startWhitelistUnlock();
            return;
        }
        if (!TARGET_PKG.equals(lp.packageName)) {
            return;
        }
        log("module loading in " + lp.packageName + " (pid=" + android.os.Process.myPid() + ")");
        // UI 写、这里只读；开关变化由 XSharedPreferences 的文件监听刷新缓存（见 ConfigReader）。
        sSubtitleConfig = new ConfigReader(null);
        hookStatusDispatcher(lp.classLoader);
        hookAsrGlobalParser(lp.classLoader);
        hookWorkManagerListeners(lp.classLoader);
        hookMonthlyDto(lp.classLoader);
        hookSubtitleLimitFlag(lp.classLoader);
        hookStopGuards(lp.classLoader);
    }

    // ================= 分轨「任意 App」：按设置页勾选的手动白名单（system_server） =================

    /**
     * 判定链全在 native，不经过 Java：
     *
     * <pre>
     * SpecailizerPLService#setMssEnable(pkg,1)
     *   → isVocalAdjustSupported(pkg)   libSpecailizerPLService.so @0x1b0b4，跑在 atlasservice
     *       → 查 mss-whitelist（数据由 mmlistservice 提供）：不在名单内 ⇒ 拒绝
     *       → 名单内 attribute bit4 置位的包，再问 isMssMusicOnly()
     *           ← 取值自 audioserver 的 mss_music_only 参数（机型默认 1）
     *           ← **同一参数还决定音频策略是否为该 App 强制 MSS 通路**
     *             （AudioPolicyManagerExtImpl::shouldNotForceMss / shouldForceMssBySession）
     * </pre>
     *
     * 关键教训：把 {@code mss_music_only} 压成 0，虽然能让「名单外的 App」也打开面板，
     * 但同时让策略层不再强制 MSS 通路 —— 现象是**面板可用、拖滑块毫无听感变化**
     * （audioserver 日志：{@code chooseWhichTrackToProcess_l ... tracks[0|0] voc_adj_on[0]}）。
     * 故本模块**绝不触碰该参数**，只做「把 App 加进名单」这一件事。
     *
     * 做法（仅 system_server 内，按设置页的**手动白名单**生成；契约见 docs/06-module-ui.md）：
     * <ol>
     *   <li>以**内置**白名单 {@code /system_ext/etc/Multimedia_Daemon_List.xml} 为底（version 最高、
     *       内容最新），原样保留其全部条目；</li>
     *   <li>只为**已勾选**的包改写/追加 {@code attribute=3}（bit0 支持人声调节、bit4 清零）——
     *       未勾选的内置条目一律不动（增量语义）；</li>
     *   <li>勾选为空或开关关闭 ⇒ 写「内置原样 + {@code <version>0</version>}」，
     *       version 低于内置 ⇒ 内置文件胜出 ⇒ 回到出厂行为；</li>
     *   <li>否则把 {@code <version>} 提到 {@code 20991231}（必须高于内置文件），写入可写的在线白名单
     *       {@code /data/oplus/multimedia/Multimedia_Daemon_Online_List.xml}（就地截断写：SELinux
     *       只允许 write、不允许 rename）；</li>
     *   <li>{@code SystemProperties.set("ctl.restart","mmlistservice")} 让解析白名单的原生进程重读
     *       （策略依据：{@code allow system_server ctl_restart_prop (property_service (set))}；
     *        而 {@code process signal} 对 mmlistservice 不允许 ⇒ kill 走不通）。</li>
     * </ol>
     * 配置由 UI 进程写、这里只读（{@link ConfigReader}）；变更经 XSharedPreferences 文件监听通知，
     * 因此改设置后无需重启设备。
     */
    private static void startWhitelistUnlock() {
        sConfig = new ConfigReader(new ConfigReader.Listener() {
            @Override
            public void onConfigChanged() {
                synchronized (CONFIG_LOCK) {
                    configDirty = true;
                    CONFIG_LOCK.notifyAll();
                }
            }
        });
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                whitelistLoop();
            }
        }, "mss-whitelist-unlock");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 单工作线程：等配置 → 写白名单 → 再等变更通知。
     *
     * <p>读到配置之后**完全是事件驱动**（{@code onConfigChanged} → {@code notifyAll}），不做变更轮询；
     * 只有在「prefs 尚不可读 / 内置文件尚不可读」（首次安装、开机早期）时才每 5 秒重试一次。
     */
    private static void whitelistLoop() {
        while (true) {
            synchronized (CONFIG_LOCK) {
                if (!configDirty) {
                    try {
                        CONFIG_LOCK.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
                configDirty = false;
            }
            boolean ok;
            try {
                ok = applyWhitelist();
            } catch (Throwable th) {
                log("whitelist: apply failed: " + th);
                ok = false;
            }
            if (ok) {
                continue;
            }
            try {
                Thread.sleep(5_000L);
            } catch (InterruptedException e) {
                return;
            }
            synchronized (CONFIG_LOCK) {
                configDirty = true;
            }
        }
    }

    /** 按当前配置应用一次：生成在线白名单并让 mmlistservice 重读。 */
    private static boolean applyWhitelist() throws Throwable {
        ConfigReader cfg = sConfig;
        if (cfg == null || !cfg.isAvailable()) {
            // 没有用户意图（prefs 读不到）就不动系统文件；工作线程每 5 秒重试，直到读到配置。
            if (!prefsMissingLogged) {
                prefsMissingLogged = true;
                log("whitelist: prefs 不可读，跳过写入");
            }
            return false;
        }
        File builtin = new File(BUILTIN_LIST_PATH);
        if (!builtin.exists()) {
            log("whitelist: 内置文件不存在，跳过");
            return true;
        }
        String base = readAll(builtin);
        int i = base.indexOf("<mss-whitelist>");
        int j = base.indexOf("</mss-whitelist>");
        if (i < 0 || j < i) {
            log("whitelist: 内置文件缺少 <mss-whitelist> 段，跳过");
            return true;
        }
        File online = new File(ONLINE_LIST_PATH);
        Set<String> want = cfg.stemWhitelist();

        if (!cfg.stemUnlock() || want.isEmpty()) {
            // 关闭（或白名单为空）：写内置原样 + version 0 ⇒ version 低于内置 ⇒ 内置文件胜出 = 出厂行为。
            writeAll(online, withVersion(base, "0"));
            log("whitelist: 关闭（写内置原样 + version 0）");
            restartInitService(SERVICE_MMLISTSERVICE);
            return true;
        }

        String head = base.substring(0, i);
        String block = base.substring(i + "<mss-whitelist>".length(), j);
        String tail = base.substring(j);

        Set<String> have = new HashSet<String>();
        Matcher nm = Pattern.compile("<name>\\s*([^<]+?)\\s*</name>").matcher(block);
        while (nm.find()) {
            have.add(nm.group(1).trim());
        }

        // 只对**已勾选**的包改写 attribute：写成 3（bit0 支持人声调节 + bit4 清零）。
        // 未勾选的内置条目原样保留 —— 这是「增量语义」，也是与 v1.10「无条件全量清 bit4」的区别。
        int cleared = 0;
        StringBuffer buf = new StringBuffer();
        Matcher em = ENTRY.matcher(block);
        while (em.find()) {
            String pkg = em.group(1) == null ? null : em.group(1).trim();
            String gap = em.group(2) == null ? "" : em.group(2);
            String attr = em.group(3);
            String digits = em.group(4);
            String repl = em.group(0);
            if (pkg != null && want.contains(pkg)) {
                if (attr == null) {
                    String sep = gap.length() == 0 ? "\n        " : gap;
                    repl = "<name>" + em.group(1) + "</name>" + sep
                            + "<attribute>" + LIST_ATTRIBUTE + "</attribute>";
                    cleared++;
                } else if (!LIST_ATTRIBUTE.equals(digits == null ? "" : digits.trim())) {
                    repl = "<name>" + em.group(1) + "</name>" + gap
                            + "<attribute>" + LIST_ATTRIBUTE + "</attribute>";
                    cleared++;
                }
            }
            em.appendReplacement(buf, Matcher.quoteReplacement(repl));
        }
        em.appendTail(buf);

        StringBuilder sb = new StringBuilder("<mss-whitelist>").append(buf);
        int added = 0;
        List<String> sorted = new ArrayList<String>(want);
        Collections.sort(sorted);
        for (String p : sorted) {
            if (p == null || p.length() == 0 || have.contains(p)) {
                continue;
            }
            sb.append("\n        <name>").append(p).append("</name>")
              .append("\n        <attribute>").append(LIST_ATTRIBUTE).append("</attribute>");
            added++;
        }
        sb.append("\n    ");
        String out = withVersion(head + sb + tail, LIST_VERSION);
        writeAll(online, out);
        log("whitelist: 保留 " + have.size() + " 条原有条目（" + cleared + " 条按勾选清零），追加 "
                + added + " 条（attribute=" + LIST_ATTRIBUTE + ", version=" + LIST_VERSION + "）");
        restartInitService(SERVICE_MMLISTSERVICE);
        return true;
    }

    /** 替换第一个 {@code <version>} 的值（沿用旧实现对内置文件结构的假设）。 */
    private static String withVersion(String xml, String version) {
        return xml.replaceFirst("<version>\\s*\\d+\\s*</version>", "<version>" + version + "</version>");
    }

    /** 让 init 重启原生服务（system_server 有 ctl_restart_prop 的 set 权限）。 */
    private static void restartInitService(String name) {
        try {
            XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.os.SystemProperties", null),
                    "set", "ctl.restart", name);
            log("restart: ctl.restart " + name);
        } catch (Throwable t) {
            log("restart(" + name + ") failed: " + t);
        }
    }

    private static String readAll(File f) throws Throwable {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    /** 就地截断写入（SELinux 只允许 write，不允许 rename，故不用临时文件+改名）。 */
    private static void writeAll(File f, String content) throws Throwable {
        FileOutputStream out = new FileOutputStream(f, false);
        try {
            out.write(content.getBytes("UTF-8"));
            out.flush();
        } finally {
            out.close();
        }
    }

    private static boolean isLimitStatus(int code) {
        return code == CODE_USE_TIME_TOO_LONG
                || code == CODE_USE_TIME_LIMIT_REACHED
                || code == CODE_MONTHLY_LIMIT_REACHED;
    }

    private static boolean isLimitRaw(int code) {
        return code == RAW_USE_TIME_TOO_LONG
                || code == RAW_USE_TIME_LIMIT_REACHED
                || code == RAW_MONTHLY_LIMIT_REACHED;
    }

    /** 引擎 -> 监听器 状态分发器：所有状态码都经此转发。 */
    private static void hookStatusDispatcher(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.coloros.translate.engine.asr.s", cl, "onResultStatus",
                    int.class, int.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!subtitleUnlocked()) {
                                return;
                            }
                            int from = (Integer) param.args[0];
                            int code = (Integer) param.args[1];
                            String msg = (String) param.args[2];
                            log("status from=" + from + " code=" + code + " msg=" + msg);
                            if (isLimitStatus(code)) {
                                log("drop status code " + code + " @ engine dispatcher");
                                param.setResult(null);
                            }
                        }
                    });
            log("hooked engine dispatcher s#onResultStatus");
        } catch (Throwable t) {
            log("hookStatusDispatcher failed: " + t);
        }
    }

    /** AIUnit 原始错误码 -> e4.c 的映射入口。 */
    private static void hookAsrGlobalParser(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.coloros.translate.engine.asr.asrclient.h", cl, "e",
                    int.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!subtitleUnlocked()) {
                                return;
                            }
                            int raw = (Integer) param.args[0];
                            if (isLimitRaw(raw)) {
                                log("drop raw asr code " + raw + " @ AsrGlobalParser");
                                param.setResult(null);
                            }
                        }
                    });
            log("hooked AsrGlobalParser h#e");
        } catch (Throwable t) {
            log("hookAsrGlobalParser failed: " + t);
        }
    }

    /** 具体 WorkManager 监听器（防御性，覆盖绕过 s 的路径）。 */
    private static void hookWorkManagerListeners(ClassLoader cl) {
        final String[] targets = {
                "com.coloros.accessibilityassistant.subtitle.g0$d",
                "com.coloros.accessibilityassistant.subtitle.globalsummary.GlobalAsrWorkManager$e",
        };
        for (String cls : targets) {
            try {
                XposedHelpers.findAndHookMethod(
                        cls, cl, "onResultStatus",
                        int.class, int.class, String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (!subtitleUnlocked()) {
                                    return;
                                }
                                int code = (Integer) param.args[1];
                                if (isLimitStatus(code)) {
                                    log("drop status code " + code + " @ " + param.method.getDeclaringClass().getName());
                                    param.setResult(null);
                                }
                            }
                        });
                log("hooked listener " + cls + "#onResultStatus");
            } catch (Throwable t) {
                log("hook " + cls + " failed: " + t);
            }
        }
    }

    /** UI 上的「本月剩余时长」改为极大值。 */
    private static void hookMonthlyDto(ClassLoader cl) {
        final String cls = "com.coloros.accessibilityassistant.subtitle.globalsummary.GlobalAsrDto";
        final String[] methods = {"getMonthlyAvailableDuration", "getMonthlyMaxAvailableDuration"};
        for (String m : methods) {
            try {
                XposedHelpers.findAndHookMethod(
                        cls, cl, m,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (!subtitleUnlocked()) {
                                    return;
                                }
                                param.setResult(Long.valueOf(UNLIMITED_DURATION));
                            }
                        });
                log("hooked " + cls + "#" + m);
            } catch (Throwable t) {
                log("hook " + cls + "#" + m + " failed: " + t);
            }
        }
    }

    /** 字幕 WorkManager 的「已达上限」标志：强制为 false，避免后续状态码被忽略。 */
    private static void hookSubtitleLimitFlag(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.coloros.accessibilityassistant.subtitle.g0", cl, "X",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!subtitleUnlocked()) {
                                return;
                            }
                            param.setResult(Boolean.FALSE);
                        }
                    });
            log("hooked subtitle limit flag g0#X");
        } catch (Throwable t) {
            log("hook g0#X failed: " + t);
        }
    }

    /** 限制到达时的「停止字幕/摘要」动作：兜底置空，防止状态码从其他路径漏过。 */
    private static void hookStopGuards(ClassLoader cl) {
        hookVoidNoop("com.coloros.accessibilityassistant.subtitle.g0", cl, "S");
        hookVoidNoop("com.coloros.accessibilityassistant.subtitle.globalsummary.GlobalAsrWorkManager", cl, "x0");
        hookVoidNoop("com.coloros.accessibilityassistant.subtitle.globalsummary.GlobalAsrWorkManager", cl, "T0");
    }

    private static void hookVoidNoop(String cls, ClassLoader cl, String method) {
        try {
            XposedHelpers.findAndHookMethod(
                    cls, cl, method,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!subtitleUnlocked()) {
                                return;
                            }
                            log("suppress " + param.method.getDeclaringClass().getSimpleName() + "#" + param.method.getName());
                            param.setResult(null);
                        }
                    });
            log("hooked stop guard " + cls + "#" + method);
        } catch (Throwable t) {
            log("hook " + cls + "#" + method + " failed: " + t);
        }
    }

    /** hook 侧统一日志出口（规则 R5）：TAG 固定，便于真机 `logcat -s ColorOSSubtitleUnlock`。 */
    static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
        Log.i(TAG, msg);
    }
}