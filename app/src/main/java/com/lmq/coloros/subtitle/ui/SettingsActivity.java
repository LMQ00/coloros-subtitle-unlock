package com.lmq.coloros.subtitle.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.lmq.coloros.subtitle.Prefs;
import com.lmq.coloros.subtitle.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 模块设置页：两个功能开关 + 分轨白名单 + 状态。
 *
 * <p>本类只负责**写配置**（AGENTS.md 规则 11）：写入 `SharedPreferences`
 * （{@code MODE_WORLD_READABLE}），由 hook 侧 {@code ConfigReader} 读取。
 * 契约与生效链路见 {@code docs/06-module-ui.md}。
 */
public class SettingsActivity extends AppCompatActivity {

    /** 状态区里显示「是否已安装」的目标 App（与 AndroidManifest 的 queries 一致）。 */
    private static final String[] WATCHED = {
            "com.coloros.accessibilityassistant",
            "com.oplus.smartmediacontroller",
    };

    private SharedPreferences prefs;
    /** true = {@code MODE_WORLD_READABLE} 不可用，已降级为 MODE_PRIVATE（hook 侧读不到）。 */
    private boolean channelDegraded;

    private SwitchMaterial switchSubtitle;
    private SwitchMaterial switchStem;
    private ListView listWhitelist;
    private TextView textEmpty;
    private TextView textStatus;
    private Button buttonAdd;

    private final List<String> rows = new ArrayList<String>();
    private final List<String> pkgs = new ArrayList<String>();
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        toolbar.setTitle(R.string.settings_title);
        // targetSdk 35 起强制 edge-to-edge：给根布局补系统栏内边距，
        // 否则标题会被状态栏压住、底部状态文字会被导航栏压住（真机 v1.11 实测）。
        final View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
                return insets;
            }
        });
        switchSubtitle = (SwitchMaterial) findViewById(R.id.switch_subtitle);
        switchStem = (SwitchMaterial) findViewById(R.id.switch_stem);
        listWhitelist = (ListView) findViewById(R.id.list_whitelist);
        textEmpty = (TextView) findViewById(R.id.text_empty);
        textStatus = (TextView) findViewById(R.id.text_status);
        buttonAdd = (Button) findViewById(R.id.button_add_app);

        prefs = openPrefs();
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows);
        listWhitelist.setAdapter(adapter);

        // 先回填状态，再挂监听，避免初始化触发一次保存
        switchSubtitle.setChecked(Prefs.subtitleUnlock(prefs));
        switchStem.setChecked(Prefs.stemUnlock(prefs));
        pkgs.clear();
        pkgs.addAll(Prefs.stemWhitelist(prefs));
        switchSubtitle.setOnCheckedChangeListener((buttonView, isChecked) -> save());
        switchStem.setOnCheckedChangeListener((buttonView, isChecked) -> save());
        buttonAdd.setOnClickListener(v -> pickApps());
        listWhitelist.setOnItemClickListener((parent, view, position, id) -> confirmRemove(position));
        listWhitelist.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmRemove(position);
            return true;
        });

        refreshWhitelist();
        refreshStatus();
    }

    /** MODE_WORLD_READABLE 不可用时降级（hook 侧将读不到配置），状态区会提示。 */
    private SharedPreferences openPrefs() {
        try {
            return Prefs.writable(this);
        } catch (SecurityException e) {
            channelDegraded = true;
            return getSharedPreferences(Prefs.FILE_NAME, Context.MODE_PRIVATE);
        }
    }

    private void pickApps() {
        AppPickerDialog.show(this, new HashSet<String>(pkgs), new AppPickerDialog.OnPickedListener() {
            @Override
            public void onPicked(Set<String> picked) {
                if (picked == null || picked.isEmpty()) {
                    return;
                }
                pkgs.addAll(picked);
                refreshWhitelist();
                save();
            }
        });
    }

    private void confirmRemove(final int position) {
        if (position < 0 || position >= pkgs.size()) {
            return;
        }
        final String pkg = pkgs.get(position);
        new AlertDialog.Builder(this)
                .setTitle(R.string.remove_title)
                .setMessage(getString(R.string.remove_msg, labelOf(pkg)))
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pkgs.remove(pkg);
                        refreshWhitelist();
                        save();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void save() {
        Set<String> set = new HashSet<String>(pkgs);
        prefs.edit()
                .putBoolean(Prefs.KEY_SUBTITLE_UNLOCK, switchSubtitle.isChecked())
                .putBoolean(Prefs.KEY_STEM_UNLOCK, switchStem.isChecked())
                .putStringSet(Prefs.KEY_STEM_WHITELIST, set)
                .apply();
        refreshStatus();
        Toast.makeText(this,
                channelDegraded ? R.string.saved_degraded : R.string.saved,
                Toast.LENGTH_SHORT).show();
    }

    private void refreshWhitelist() {
        Collections.sort(pkgs, String.CASE_INSENSITIVE_ORDER);
        rows.clear();
        for (String p : pkgs) {
            rows.add(labelOf(p) + "\n" + p);
        }
        adapter.notifyDataSetChanged();
        textEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void refreshStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(channelDegraded
                ? R.string.status_channel_bad : R.string.status_channel_ok));
        sb.append('\n').append(getString(R.string.status_selected, pkgs.size()));
        for (String p : WATCHED) {
            sb.append('\n').append(getString(
                    isInstalled(p) ? R.string.status_installed : R.string.status_missing, p));
        }
        sb.append('\n').append(getString(R.string.status_note));
        textStatus.setText(sb.toString());
    }

    /** 应用标签；取不到（不可见/已卸载）时退回包名。 */
    private String labelOf(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            CharSequence label = getPackageManager().getApplicationLabel(ai);
            return label == null ? pkg : label.toString();
        } catch (Throwable t) {
            return pkg;
        }
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
