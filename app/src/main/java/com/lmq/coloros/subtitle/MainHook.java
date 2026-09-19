package com.lmq.coloros.subtitle;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 模块：解除 ColorOS「AI 语音摘记」(com.coloros.accessibilityassistant)
 * 开启字幕功能的每月 120 分钟时长限制。
 *
 * 逆向结论（APK 16.3.12）：
 *   云端 ASR 通过 AIUnit 返回错误码 3000803（"月额度已达限"），
 *   经 com.coloros.translate.engine.asr.asrclient.h#e 映射为 e4.c.ASR_MONTHLY_LIMIT_REACHED(-2020)，
 *   再由引擎分发器 com.coloros.translate.engine.asr.s#onResultStatus 转发给各 WorkManager，
 *   最终 GlobalSubtitleWorkManager 停止字幕并弹「已达上限」提示。
 *
 * 本模块在三个层面丢弃这些限制状态码，使字幕/摘记继续运行：
 *   1) AsrGlobalParser 入口（原始码 3000801/3000802/3000803）
 *   2) 引擎 -> 监听器分发器 s#onResultStatus（状态码 -2017/-2018/-2020）
 *   3) 具体 WorkManager 监听器（防御性）
 * 并把 UI 上的「本月剩余时长」改写为极大值。
 *
 * 注意：配额由云端/系统 AIUnit 判定，本模块只解除客户端对限制的反应。
 *       若云端在返回 3000803 后彻底停止下发识别结果，则客户端无法恢复。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "ColorOSSubtitleUnlock";
    private static final String TARGET_PKG = "com.coloros.accessibilityassistant";

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