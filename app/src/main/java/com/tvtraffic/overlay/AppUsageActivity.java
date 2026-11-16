package com.tvtraffic.overlay;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 应用流量排行。
 *
 * 排序方式以菜单形式常驻显示（流量大→小 / 小→大 / 名称 A→Z / Z→A），点哪项就按哪项排，
 * 不再用单按钮循环切换。每行显示该应用的流量总和，下面一行细分上下行。
 * 选中某个应用按 OK 键可加入/移出上传异常忽略名单。
 */
public class AppUsageActivity extends Activity {

    private static final int MODE_BOOT = 0;
    private static final int MODE_7D = 1;
    private static final int MODE_30D = 2;

    private static final int SORT_TRAFFIC_DESC = 0;
    private static final int SORT_TRAFFIC_ASC = 1;
    private static final int SORT_NAME_ASC = 2;
    private static final int SORT_NAME_DESC = 3;

    private int mode = MODE_BOOT;
    private int sort = SORT_TRAFFIC_DESC;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 全部查询结果 */
    private final List<AppUsageRepository.AppUsage> data = new ArrayList<>();

    private Adapter adapter;
    private TextView tvSummary;
    private ListView list;
    private Button btnRange;
    private final TextView[] chips = new TextView[4];
    private Prefs prefs;

    private long rangeStart;
    private String selectedPkg;
    private boolean firstLoad = true;
    private volatile boolean loading = false;
    private volatile boolean alive = true;

    private final Collator collator = Collator.getInstance(Locale.getDefault());

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            reload();
            if (alive) handler.postDelayed(this, 10000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_usage);
        Fonts.apply(this, getWindow().getDecorView());

        prefs = new Prefs(this);
        sort = prefs.usageSort();

        tvSummary = findViewById(R.id.tv_summary);
        btnRange = findViewById(R.id.btn_range);
        list = findViewById(R.id.list);

        chips[SORT_TRAFFIC_DESC] = findViewById(R.id.sort_traffic_desc);
        chips[SORT_TRAFFIC_ASC] = findViewById(R.id.sort_traffic_asc);
        chips[SORT_NAME_ASC] = findViewById(R.id.sort_name_asc);
        chips[SORT_NAME_DESC] = findViewById(R.id.sort_name_desc);
        for (int i = 0; i < chips.length; i++) {
            final int idx = i;
            chips[i].setOnClickListener(v -> selectSort(idx));
        }

        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setItemsCanFocus(false);
        list.setEmptyView(findViewById(R.id.tv_empty));
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < data.size()) showAppDialog(data.get(position));
        });

        btnRange.setOnClickListener(v -> {
            if (Compat.isLegacyUsage()) {
                // 4.x 没有 NetworkStatsManager，切范围也查不出东西
                toastLegacy();
                return;
            }
            mode = (mode + 1) % 3;
            updateRangeButton();
            reload();
        });
        if (Compat.isLegacyUsage()) {
            mode = MODE_BOOT;
            btnRange.setEnabled(false);
            btnRange.setAlpha(0.6f);
        }
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        updateRangeButton();
        updateSortChips();
        reload();
        list.postDelayed(() -> {
            if (!alive) return;
            list.requestFocus();
            if (list.getSelectedItemPosition() < 0 && !data.isEmpty()) list.setSelection(0);
        }, 600L);
        handler.postDelayed(refreshTask, 10000L);
    }

    @Override
    protected void onDestroy() {
        alive = false;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ------------------------------------------------------------ 顶栏状态

    private void updateRangeButton() {
        switch (mode) {
            case MODE_7D:
                btnRange.setText(R.string.btn_range_7d);
                break;
            case MODE_30D:
                btnRange.setText(R.string.btn_range_30d);
                break;
            case MODE_BOOT:
            default:
                btnRange.setText(R.string.btn_range_boot);
                break;
        }
    }

    private void selectSort(int index) {
        if (index == sort) return;
        sort = index;
        prefs.setUsageSort(index);
        updateSortChips();
        applyView();
    }

    private void updateSortChips() {
        for (int i = 0; i < chips.length; i++) {
            if (chips[i] == null) continue;
            chips[i].setSelected(i == sort);
            chips[i].setTextColor(i == sort ? 0xFFEAF6FF : 0xFFD6E3EE);
        }
    }

    private long startTime() {
        long now = System.currentTimeMillis();
        switch (mode) {
            case MODE_7D:
                return now - 7L * 24 * 3600 * 1000;
            case MODE_30D:
                return now - 30L * 24 * 3600 * 1000;
            case MODE_BOOT:
            default:
                return AppUsageRepository.bootTimeMillis();
        }
    }

    // ------------------------------------------------------------ 数据加载

    private void reload() {
        if (loading) return;
        loading = true;
        final long start = startTime();
        final long end = System.currentTimeMillis();
        rangeStart = start;

        new Thread(() -> {
            final List<AppUsageRepository.AppUsage> result;
            try {
                result = AppUsageRepository.query(getApplicationContext(), start, end);
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    loading = false;
                    tvSummary.setText(getString(R.string.load_failed_fmt, t.getClass().getSimpleName()));
                });
                return;
            }
            runOnUiThread(() -> {
                loading = false;
                if (!alive) return;
                data.clear();
                data.addAll(result);
                applyView();
            });
        }, "usage-query").start();
    }

    /** 排序 + 刷新列表，尽量按 uid 保持遥控器选中项 */
    private void applyView() {
        String keepPkg = selectedPkg;
        int sel = list.getSelectedItemPosition();
        if (sel >= 0 && sel < data.size()) {
            keepPkg = data.get(sel).packageName;
        }

        sortData();
        adapter.notifyDataSetChanged();
        updateSummary();

        boolean restored = false;
        if (keepPkg != null) {
            selectedPkg = keepPkg;
            for (int i = 0; i < data.size(); i++) {
                if (keepPkg.equals(data.get(i).packageName)) {
                    list.setSelection(i);
                    restored = true;
                    break;
                }
            }
        }
        if (!restored && !data.isEmpty()) list.setSelection(0);

        if (firstLoad && !data.isEmpty()) {
            firstLoad = false;
            list.post(() -> {
                if (!alive) return;
                list.requestFocus();
                list.setSelection(0);
            });
        }
    }

    private void sortData() {
        Collections.sort(data, new Comparator<AppUsageRepository.AppUsage>() {
            @Override
            public int compare(AppUsageRepository.AppUsage a, AppUsageRepository.AppUsage b) {
                // 本期没有流量的应用统一沉底，避免把排行稀释
                boolean at = a.hasTraffic();
                boolean bt = b.hasTraffic();
                if (at != bt) return at ? -1 : 1;
                if (!at) return collator.compare(nameOf(a), nameOf(b));

                switch (sort) {
                    case SORT_TRAFFIC_ASC: {
                        int c = Compat.cmp(a.total(), b.total());
                        return c != 0 ? c : nameOf(a).compareToIgnoreCase(nameOf(b));
                    }
                    case SORT_NAME_ASC: {
                        int c = collator.compare(nameOf(a), nameOf(b));
                        return c != 0 ? c : Compat.cmp(b.total(), a.total());
                    }
                    case SORT_NAME_DESC: {
                        int c = collator.compare(nameOf(b), nameOf(a));
                        return c != 0 ? c : Compat.cmp(b.total(), a.total());
                    }
                    case SORT_TRAFFIC_DESC:
                    default: {
                        int c = Compat.cmp(b.total(), a.total());
                        return c != 0 ? c : collator.compare(nameOf(a), nameOf(b));
                    }
                }
            }
        });
    }

    private String nameOf(AppUsageRepository.AppUsage u) {
        String s = u.label == null ? u.packageName : u.label;
        return s == null ? "" : s;
    }

    private void updateSummary() {
        // 共享 uid 的多个包会各占一行，流量相同，求和时必须按 uid 去重
        java.util.Set<Integer> counted = new java.util.HashSet<>();
        long rx = 0, tx = 0;
        int withTraffic = 0;
        for (AppUsageRepository.AppUsage u : data) {
            if (!u.hasTraffic()) continue;
            withTraffic++;
            if (!counted.add(u.uid)) continue;
            rx += u.rx;
            tx += u.tx;
        }
        String from = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(new Date(rangeStart));
        int sourceRes;
        if (AppUsageRepository.lastFromSystemStats) {
            sourceRes = R.string.source_system;
        } else if (Compat.isLegacyUsage()) {
            sourceRes = R.string.source_legacy;
        } else {
            sourceRes = R.string.source_fallback;
        }
        String source = getString(sourceRes);
        tvSummary.setText(getString(R.string.summary_fmt, from, data.size(), withTraffic,
                Fmt.volume(rx), Fmt.volume(tx), Fmt.volume(rx + tx), source));
        // 兜底数据明显不全，用橙色提示，避免误以为应用真的没跑流量
        tvSummary.setTextColor(AppUsageRepository.lastFromSystemStats ? 0xFF7E93A8 : 0xFFFFB74D);
    }

    private void toastLegacy() {
        Toast.makeText(this, R.string.toast_usage_legacy, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ 应用详情

    private void showAppDialog(final AppUsageRepository.AppUsage u) {
        selectedPkg = u.packageName;

        StringBuilder msg = new StringBuilder();
        msg.append(u.packageName);
        if (u.shared) msg.append(getString(R.string.dialog_shared_suffix));
        msg.append('\n').append(getString(R.string.dialog_total_fmt, Fmt.volume(u.total())));
        msg.append('\n').append(getString(R.string.dialog_detail_fmt,
                Fmt.volume(u.rx), Fmt.volume(u.tx)));
        msg.append('\n').append(getString(R.string.dialog_type_fmt,
                getString(u.system ? R.string.type_system : R.string.type_user)));
        if (!u.hasTraffic()) msg.append("\n\n").append(getString(R.string.dialog_no_traffic));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(nameOf(u))
                .setMessage(msg.toString())
                .setPositiveButton(R.string.dialog_close, null)
                .create();
        // 关掉弹窗后把焦点还给列表，否则遥控器会失去落点
        dialog.setOnDismissListener(d -> {
            if (list != null) list.requestFocus();
        });
        dialog.show();
        try {
            Fonts.apply(this, dialog.getWindow().getDecorView());
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ adapter

    private class Adapter extends BaseAdapter {

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(AppUsageActivity.this)
                        .inflate(R.layout.item_app, parent, false);
            }
            AppUsageRepository.AppUsage u = data.get(position);

            ImageView icon = v.findViewById(R.id.iv_icon);
            TextView name = v.findViewById(R.id.tv_name);
            TextView pkg = v.findViewById(R.id.tv_pkg);
            TextView total = v.findViewById(R.id.tv_total);
            TextView detail = v.findViewById(R.id.tv_detail);

            if (u.icon != null) {
                icon.setImageDrawable(u.icon);
            } else {
                icon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            name.setTypeface(Fonts.bold(AppUsageActivity.this));
            pkg.setTypeface(Fonts.regular(AppUsageActivity.this));
            total.setTypeface(Fonts.bold(AppUsageActivity.this));
            detail.setTypeface(Fonts.regular(AppUsageActivity.this));

            name.setText(nameOf(u));
            String pkgText = u.packageName;
            if (u.shared) pkgText = pkgText + getString(R.string.item_shared_suffix);
            pkg.setText(pkgText);

            // 流量总和放主位，上下行细分放次位；本期没流量的应用灰显
            boolean has = u.hasTraffic();
            name.setTextColor(has ? 0xFFF2F7FC : 0xFF75879A);
            total.setTextColor(has ? 0xFFF2F7FC : 0xFF67798C);
            detail.setTextColor(has ? 0xFF7E93A8 : 0xFF4E6070);
            total.setText(has ? Fmt.volume(u.total()) : "0 B");
            detail.setText(has
                    ? "↓ " + Fmt.volume(u.rx) + "　↑ " + Fmt.volume(u.tx)
                    : getString(R.string.item_no_traffic));

            return v;
        }
    }
}
