package com.lmq.coloros.subtitle;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.Set;

import de.robv.android.xposed.XSharedPreferences;

/**
 * hook 侧**只读**配置（system_server 与目标 App 进程内各一份）。
 *
 * <p>要点：
 * <ul>
 *   <li>通道固定为 {@link XSharedPreferences}（AGENTS.md 规则 12），只读不写（规则 11）；</li>
 *   <li>变更由 prefs 物理文件的监听触发；LSPosed 的监听回调里 **key 恒为 null**，
 *       所以回调内一律 {@link #reload()} 重新取值（见 {@code docs/06-module-ui.md}）；</li>
 *   <li>监听回调跑在 FileObserver 线程：这里只做 {@code reload()} + 通知业务（轻量），
 *       重活（写白名单）由 {@code MainHook} 的工作线程做。</li>
 * </ul>
 */
public final class ConfigReader implements SharedPreferences.OnSharedPreferenceChangeListener {

    /** 配置变化后的动作，由调用方决定在哪个线程执行重活。 */
    public interface Listener {
        void onConfigChanged();
    }

    private final XSharedPreferences pref;
    private final Listener listener;

    private volatile boolean available;
    private volatile boolean subtitleUnlock = Prefs.DEFAULT_SUBTITLE_UNLOCK;
    private volatile boolean stemUnlock = Prefs.DEFAULT_STEM_UNLOCK;
    private volatile Set<String> stemWhitelist = Collections.emptySet();

    public ConfigReader(Listener listener) {
        this.listener = listener;
        XSharedPreferences p = null;
        try {
            p = new XSharedPreferences(BuildConfig.APPLICATION_ID, Prefs.FILE_NAME);
        } catch (Throwable t) {
            MainHook.log("config: XSharedPreferences 创建失败: " + t);
        }
        this.pref = p;
        reload();
        if (p != null) {
            try {
                p.registerOnSharedPreferenceChangeListener(this);
            } catch (Throwable t) {
                MainHook.log("config: 注册变更监听失败: " + t);
            }
        }
    }

    /** @return 配置是否可用（prefs 文件存在且可读）。 */
    public boolean isAvailable() {
        return available;
    }

    public boolean subtitleUnlock() {
        return subtitleUnlock;
    }

    public boolean stemUnlock() {
        return stemUnlock;
    }

    public Set<String> stemWhitelist() {
        return stemWhitelist;
    }

    /** 重新读文件并刷新缓存。 */
    public boolean reload() {
        XSharedPreferences p = pref;
        if (p == null) {
            available = false;
            return false;
        }
        try {
            if (!p.getFile().canRead()) {
                available = false;
                return false;
            }
            p.reload();
            subtitleUnlock = Prefs.subtitleUnlock(p);
            stemUnlock = Prefs.stemUnlock(p);
            stemWhitelist = Prefs.stemWhitelist(p);
            available = true;
            return true;
        } catch (Throwable t) {
            MainHook.log("config: reload 失败: " + t);
            available = false;
            return false;
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        // key 恒为 null（XSharedPreferences 是文件级监听）⇒ 必须 reload 后重取全部值。
        boolean ok = reload();
        MainHook.log("config: 配置变更已读入（reload=" + ok + "）");
        Listener l = listener;
        if (l != null) {
            try {
                l.onConfigChanged();
            } catch (Throwable t) {
                MainHook.log("config: 通知业务失败: " + t);
            }
        }
    }
}
