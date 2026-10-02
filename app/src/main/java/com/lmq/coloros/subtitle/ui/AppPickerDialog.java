package com.lmq.coloros.subtitle.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;

import com.lmq.coloros.subtitle.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 应用选择器：只列**有启动图标的用户 App**（不含模块自身与已在白名单里的），支持搜索、多选。
 *
 * <p>只负责「挑出要新增的包」；「并集 + 保存」由 {@link SettingsActivity} 做，避免误删。
 */
public final class AppPickerDialog {

    /** 选择完成回调。 */
    public interface OnPickedListener {
        void onPicked(Set<String> packages);
    }

    private AppPickerDialog() {
    }

    public static void show(final Context ctx, final Set<String> already, final OnPickedListener listener) {
        final List<AppEntry> all = loadApps(ctx);
        final Set<String> checked = new HashSet<String>();
        final List<AppEntry> shown = new ArrayList<AppEntry>();
        for (AppEntry e : all) {
            if (already == null || !already.contains(e.pkg)) {
                shown.add(e);
            }
        }

        View content = LayoutInflater.from(ctx).inflate(R.layout.dialog_app_picker, null);
        final EditText search = (EditText) content.findViewById(R.id.edit_search);
        final ListView list = (ListView) content.findViewById(R.id.list_apps);
        final AppAdapter adapter = new AppAdapter(ctx, shown, checked);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= shown.size()) {
                    return;
                }
                String pkg = shown.get(position).pkg;
                if (!checked.remove(pkg)) {
                    checked.add(pkg);
                }
                adapter.notifyDataSetChanged();
            }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String q = s == null ? "" : s.toString().trim().toLowerCase();
                shown.clear();
                for (AppEntry e : all) {
                    if (already != null && already.contains(e.pkg)) {
                        continue;
                    }
                    if (q.length() == 0
                            || e.label.toLowerCase().contains(q)
                            || e.pkg.toLowerCase().contains(q)) {
                        shown.add(e);
                    }
                }
                adapter.notifyDataSetChanged();
            }
        });

        new AlertDialog.Builder(ctx)
                .setTitle(R.string.add_app)
                .setView(content)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (listener != null) {
                            listener.onPicked(new HashSet<String>(checked));
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 枚举有启动图标的 App（去重、排除模块自身），按标签排序。 */
    private static List<AppEntry> loadApps(Context ctx) {
        List<AppEntry> out = new ArrayList<AppEntry>();
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
            Set<String> seen = new HashSet<String>();
            String self = ctx.getPackageName();
            for (ResolveInfo ri : infos) {
                if (ri == null || ri.activityInfo == null) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || pkg.equals(self) || !seen.add(pkg)) {
                    continue;
                }
                CharSequence label = ri.loadLabel(pm);
                out.add(new AppEntry(pkg, label == null ? pkg : label.toString()));
            }
            Collections.sort(out, new Comparator<AppEntry>() {
                @Override
                public int compare(AppEntry a, AppEntry b) {
                    return a.label.compareToIgnoreCase(b.label);
                }
            });
        } catch (Throwable t) {
            // 拿不到列表时退化为空列表，不阻塞设置页
        }
        return out;
    }

    private static final class AppEntry {
        final String pkg;
        final String label;

        AppEntry(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    private static final class AppAdapter extends BaseAdapter {
        private final Context ctx;
        private final List<AppEntry> items;
        private final Set<String> checked;

        AppAdapter(Context ctx, List<AppEntry> items, Set<String> checked) {
            this.ctx = ctx;
            this.items = items;
            this.checked = checked;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (!(view instanceof CheckedTextView)) {
                view = LayoutInflater.from(ctx).inflate(
                        android.R.layout.simple_list_item_multiple_choice, parent, false);
            }
            CheckedTextView tv = (CheckedTextView) view;
            AppEntry e = items.get(position);
            tv.setText(e.label + "\n" + e.pkg);
            tv.setChecked(checked.contains(e.pkg));
            return tv;
        }
    }
}
