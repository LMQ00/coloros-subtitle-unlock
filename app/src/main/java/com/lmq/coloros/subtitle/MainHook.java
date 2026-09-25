package com.lmq.coloros.subtitle;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.util.Log;

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
 * 2) 「声音分轨」(com.oplus.smartmediacontroller) 仅限音乐类 App 使用。
 *    逆向结论（APK 16.1.20 + 系统库反汇编）：
 *      判定在 native 服务 android::SpecailizerPLService（跑在 audioserver 内）：
 *      setMssEnable(pkg,true) 先调 isVocalAdjustSupported(pkg)，该函数查 XML 白名单
 *      "mss-whitelist" 的 attribute 值 v：bit0 必须为 1（支持人声调节）；
 *      若 v 的 bit4 置位（非音乐类，如 bilibili 的 17），还要看 isMssMusicOnly()，
 *      为真则拒绝并令 *ret = -1 —— App 收到非 0 便弹「当前应用暂不支持声音分轨」。
 *      isMssMusicOnly() 取自音频参数 mss_music_only；该参数是否被置 0 取决于
 *      OplusAtlasService 初始化时的
 *        if (!OplusFeatureConfigManager.getInstance().hasFeature("oplus.software.audio.mss_music_only"))
 *            audioManager.setParameters("mss_music_only=0");
 *      本机声明了该特性，故参数保持 1（仅音乐）。
 *    本模块在 com.oplus.atlas 进程内让该特性判定返回 false，使 Atlas 自行下发
 *    mss_music_only=0，从而放行白名单内带「非音乐类」位的 App（如 bilibili）。
 *    注意：不在 mss-whitelist 内的 App 仍然不支持。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "ColorOSSubtitleUnlock";
    private static final String TARGET_PKG = "com.coloros.accessibilityassistant";
    private static final String TARGET_PKG_ATLAS = "com.oplus.atlas";
    private static final String TARGET_PKG_SMC = "com.oplus.smartmediacontroller";

    /** 设备特性：声明后 OplusAtlasService 不再下发 mss_music_only=0（即「分轨仅音乐」）。 */
    private static final String FEATURE_MSS_MUSIC_ONLY = "oplus.software.audio.mss_music_only";

    // t3.a / e4.c 状态码
    private static final int CODE_USE_TIME_TOO_LONG = -2017;
    private static final int CODE_USE_TIME_LIMIT_REACHED = -2018;
    private static final int CODE_MONTHLY_LIMIT_REACHED = -2020;

    // AIUnit 云端原始错误码
    private static final int RAW_USE_TIME_TOO_LONG = 3000801;
    private static final int RAW_USE_TIME_LIMIT_REACHED = 3000802;
    private static final int RAW_MONTHLY_LIMIT_REACHED = 3000803;

    private static final long UNLIMITED_DURATION = 999_999_999L;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (TARGET_PKG_ATLAS.equals(lp.packageName)) {
            log("module loading in " + lp.packageName + " (pid=" + android.os.Process.myPid() + ")");
            hookMssMusicOnlyFeature(lp.classLoader);
            hookAtlasSetParameters(lp.classLoader);
            return;
        }
        if (TARGET_PKG_SMC.equals(lp.packageName)) {
            log("module loading in " + lp.packageName + " (pid=" + android.os.Process.myPid() + ")");
            hookSmcInjectParam(lp.classLoader);
            return;
        }
        if (!TARGET_PKG.equals(lp.packageName)) {
            return;
        }
        log("module loading in " + lp.packageName + " (pid=" + android.os.Process.myPid() + ")");
        hookStatusDispatcher(lp.classLoader);
        hookAsrGlobalParser(lp.classLoader);
        hookWorkManagerListeners(lp.classLoader);
        hookMonthlyDto(lp.classLoader);
        hookSubtitleLimitFlag(lp.classLoader);
        hookStopGuards(lp.classLoader);
    }

    /**
     * 「声音分轨」：让 com.oplus.atlas 认为设备未声明「分轨仅音乐」特性，
     * 从而 OplusAtlasService 自行下发 setParameters("mss_music_only=0")。
     */
    private static void hookMssMusicOnlyFeature(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.content.OplusFeatureConfigManager", cl, "hasFeature",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String name = (String) param.args[0];
                            if (FEATURE_MSS_MUSIC_ONLY.equals(name)) {
                                log("force hasFeature(" + name + ") = false");
                                param.setResult(Boolean.FALSE);
                            }
                        }
                    });
            log("hooked OplusFeatureConfigManager#hasFeature");
        } catch (Throwable t) {
            log("hookMssMusicOnlyFeature failed: " + t);
        }
    }

    /**
     * 兜底：Atlas 进程内任何 {@code AudioManager.setParameters} 都补上 {@code mss_music_only=0}。
     *
     * 主路径依赖 {@code OplusAtlasService.onCreate()} 里那一处判断；若该分支因任何原因没走到
     * （或 audioserver 重启把参数重置回构造函数默认的 1），本兜底会在下一次参数下发时重新置 0。
     * 只在 Atlas 进程内生效，不改动其他 App。
     */
    private static void hookAtlasSetParameters(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.media.AudioManager", cl, "setParameters",
                    String.class,
                    new XC_MethodHook() {
                        private int appended = 0;

                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String s = (String) param.args[0];
                            if (s == null || s.contains("mss_music_only")) {
                                return;
                            }
                            param.args[0] = s + ";mss_music_only=0";
                            if (appended < 20) {
                                appended++;
                                log("append mss_music_only=0 -> \"" + s + "\"");
                            }
                        }
                    });
            log("hooked AudioManager#setParameters (fallback)");
        } catch (Throwable t) {
            log("hookAtlasSetParameters failed: " + t);
        }
    }

    /**
     * 第三条注入路径：在目标 App（`com.oplus.smartmediacontroller`）自己的进程里下发参数。
     *
     * 该 App 的 manifest 声明了 `android.permission.MODIFY_AUDIO_SETTINGS`，所以它自己就能
     * 调用 `AudioManager.setParameters`。注入点选 `MssService.onStartCommand`——面板每次被拉起
     * 都会走这里，且**早于** App 调 `setMssEnable`，因此参数在 native 判定前已置 0。
     *
     * 好处：不必重启 Atlas 进程（也就不用重启整机），只要这个 App 的进程重建一次即可。
     */
    private static void hookSmcInjectParam(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.smartmediacontroller.MssService", cl, "onStartCommand",
                    Intent.class, int.class, int.class,
                    new XC_MethodHook() {
                        private int injected = 0;

                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Context ctx = (Context) param.thisObject;
                                AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                                if (am == null) {
                                    log("MssService: AudioManager unavailable");
                                    return;
                                }
                                am.setParameters("mss_music_only=0");
                                injected++;
                                if (injected <= 5) {
                                    log("MssService: setParameters(mss_music_only=0) #" + injected);
                                    // 诊断：读回参数，判断 audioserver 侧是否真的生效
                                    log("probe getParameters(\"mss_music_only\")=\""
                                            + am.getParameters("mss_music_only")
                                            + "\" getParameters(\"foldmode\")=\""
                                            + am.getParameters("foldmode") + "\"");
                                }
                            } catch (Throwable t) {
                                log("MssService inject failed: " + t);
                            }
                        }
                    });
            log("hooked MssService#onStartCommand");
        } catch (Throwable t) {
            log("hookSmcInjectParam failed: " + t);
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
                            log("suppress " + param.method.getDeclaringClass().getSimpleName() + "#" + param.method.getName());
                            param.setResult(null);
                        }
                    });
            log("hooked stop guard " + cls + "#" + method);
        } catch (Throwable t) {
            log("hook " + cls + "#" + method + " failed: " + t);
        }
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
        Log.i(TAG, msg);
    }
}