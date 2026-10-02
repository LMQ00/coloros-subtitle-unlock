package com.lmq.coloros.subtitle;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * UI ↔ hook 的配置契约（**唯一出处**；键名变更必须同步 {@code docs/06-module-ui.md}）。
 *
 * <p>通道约定（AGENTS.md 规则 11/12）：
 * <ul>
 *   <li>写入方**只有模块自身进程**（{@link #writable(Context)}）；</li>
 *   <li>hook 侧只读，走 {@code XSharedPreferences}（见 {@link ConfigReader}）；</li>
 *   <li>本类放在根包而非 {@code ui} 包，正是为了让 hook 侧也能引用常量而不依赖 UI 层。</li>
 * </ul>
 */
public final class Prefs {

    /** prefs 文件名。hook 侧：{@code new XSharedPreferences(BuildConfig.APPLICATION_ID, FILE_NAME)}。 */
    public static final String FILE_NAME = "xposed_conf";

    /** 字幕解锁总开关（boolean，默认 {@link #DEFAULT_SUBTITLE_UNLOCK}）。 */
    public static final String KEY_SUBTITLE_UNLOCK = "subtitle_unlock";
    /** 分轨解锁总开关（boolean，默认 {@link #DEFAULT_STEM_UNLOCK}）。 */
    public static final String KEY_STEM_UNLOCK = "stem_unlock";
    /** 需要**额外**放行的分轨白名单（StringSet，默认空集）。 */
    public static final String KEY_STEM_WHITELIST = "stem_whitelist";

    public static final boolean DEFAULT_SUBTITLE_UNLOCK = true;
    public static final boolean DEFAULT_STEM_UNLOCK = true;

    private Prefs() {
    }

    /**
     * 取用于写入的 prefs。
     *
     * <p>{@code MODE_WORLD_READABLE} 需要 LSPosed 新版 XSharedPreferences 支持
     * （模块 meta-data {@code xposedsharedprefs}，见 {@code docs/06-module-ui.md}）；
     * 特性不可用时抛 {@link SecurityException}，由调用方降级并提示用户。
     */
    public static SharedPreferences writable(Context ctx) {
        return ctx.getSharedPreferences(FILE_NAME, Context.MODE_WORLD_READABLE);
    }

    public static boolean subtitleUnlock(SharedPreferences p) {
        return p.getBoolean(KEY_SUBTITLE_UNLOCK, DEFAULT_SUBTITLE_UNLOCK);
    }

    public static boolean stemUnlock(SharedPreferences p) {
        return p.getBoolean(KEY_STEM_UNLOCK, DEFAULT_STEM_UNLOCK);
    }

    /** @return 防御性拷贝（{@code getStringSet} 的返回值不得修改）。 */
    public static Set<String> stemWhitelist(SharedPreferences p) {
        Set<String> raw = p.getStringSet(KEY_STEM_WHITELIST, Collections.<String>emptySet());
        return raw == null ? Collections.<String>emptySet() : new HashSet<String>(raw);
    }
}
