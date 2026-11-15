package com.tvtraffic.overlay;

import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.TrafficStats;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按应用汇总流量。
 *
 * 列表以「已安装应用」为准逐个列出（没有流量的也列出来，排在后面），
 * 这样不会因为某个应用当期没跑流量就从列表里消失。
 * 流量数据优先来自 NetworkStatsManager（需要「使用情况访问」权限，可跨重启），
 * 拿不到时退化为 TrafficStats 的本次开机统计。
 */
public final class AppUsageRepository {

    public static class AppUsage {
        public int uid;
        public String packageName;
        public String label;
        public long rx;
        public long tx;
        public Drawable icon;
        public boolean system;
        /** 该 uid 被多个包共享（如系统服务），标注后避免误解 */
        public boolean shared;

        public long total() {
            return rx + tx;
        }

        public boolean hasTraffic() {
            return rx > 0 || tx > 0;
        }
    }

    /** 最近一次查询是否用到了系统级统计（true）还是 TrafficStats 兜底（false） */
    public static volatile boolean lastFromSystemStats = false;

    /** 图标与名称缓存：自动刷新每 10 秒一次，不缓存的话每次都重新解析 APK 资源 */
    private static final Map<String, Drawable> ICON_CACHE = new HashMap<>();
    private static final Map<String, String> LABEL_CACHE = new HashMap<>();

    private AppUsageRepository() {
    }

    public static long bootTimeMillis() {
        return System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
    }

    public static List<AppUsage> query(Context ctx, long start, long end) {
        PackageManager pm = ctx.getPackageManager();

        Map<Integer, long[]> raw = queryByUid(ctx, start, end);
        boolean fromSystemStats = !raw.isEmpty();
        if (!fromSystemStats) {
            raw = fallbackByUid(ctx);
        }
        lastFromSystemStats = fromSystemStats;

        List<ApplicationInfo> apps;
        try {
            apps = pm.getInstalledApplications(0);
        } catch (Throwable t) {
            apps = new ArrayList<>();
        }

        // 先统计每个 uid 对应几个包，用于「共用 uid」标注
        Map<Integer, Integer> uidCount = new HashMap<>();
        for (ApplicationInfo ai : apps) {
            if (ai == null) continue;
            Integer c = uidCount.get(ai.uid);
            uidCount.put(ai.uid, c == null ? 1 : c + 1);
        }

        List<AppUsage> list = new ArrayList<>(apps.size());
        Set<String> seenPkg = new HashSet<>();
        for (ApplicationInfo ai : apps) {
            if (ai == null || ai.packageName == null) continue;
            if (!ai.enabled) continue;              // 已停用的应用不列
            if (!seenPkg.add(ai.packageName)) continue;

            AppUsage u = new AppUsage();
            u.uid = ai.uid;
            u.packageName = ai.packageName;
            u.label = labelOf(pm, ai);
            u.icon = iconOf(pm, ai);
            u.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            Integer c = uidCount.get(ai.uid);
            u.shared = c != null && c > 1;

            long[] v = raw.get(ai.uid);
            if (v != null) {
                u.rx = v[0];
                u.tx = v[1];
            }
            list.add(u);
        }

        Collections.sort(list, new Comparator<AppUsage>() {
            @Override
            public int compare(AppUsage a, AppUsage b) {
                int c = Long.compare(b.total(), a.total());
                if (c != 0) return c;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return list;
    }

    private static String labelOf(PackageManager pm, ApplicationInfo ai) {
        String cached = LABEL_CACHE.get(ai.packageName);
        if (cached != null) return cached;
        String label;
        try {
            CharSequence cs = pm.getApplicationLabel(ai);
            label = cs == null ? ai.packageName : cs.toString();
        } catch (Throwable t) {
            label = ai.packageName;
        }
        LABEL_CACHE.put(ai.packageName, label);
        return label;
    }

    private static Drawable iconOf(PackageManager pm, ApplicationInfo ai) {
        Drawable cached = ICON_CACHE.get(ai.packageName);
        if (cached != null) return cached;
        Drawable d = null;
        try {
            d = pm.getApplicationIcon(ai);
        } catch (Throwable ignored) {
        }
        if (d != null) ICON_CACHE.put(ai.packageName, d);
        return d;
    }

    private static Map<Integer, long[]> queryByUid(Context ctx, long start, long end) {
        Map<Integer, long[]> map = new HashMap<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return map;

        NetworkStatsManager nsm =
                (NetworkStatsManager) ctx.getSystemService(Context.NETWORK_STATS_SERVICE);
        if (nsm == null) return map;

        // 电视上主要是 Wi-Fi 和以太网，移动网顺带查一次
        int[] types = new int[]{
                ConnectivityManager.TYPE_WIFI,
                ConnectivityManager.TYPE_MOBILE,
                9 /* TYPE_ETHERNET，常量在部分 SDK 中不可见 */
        };

        for (int type : types) {
            NetworkStats stats = null;
            try {
                stats = nsm.querySummary(type, null, start, end);
                if (stats == null) continue;
                NetworkStats.Bucket bucket = new NetworkStats.Bucket();
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket);
                    int uid = bucket.getUid();
                    long[] v = map.get(uid);
                    if (v == null) {
                        v = new long[2];
                        map.put(uid, v);
                    }
                    v[0] += bucket.getRxBytes();
                    v[1] += bucket.getTxBytes();
                }
            } catch (Throwable ignored) {
                // 某些机型/权限不足会抛异常，跳过该网络类型
            } finally {
                if (stats != null) {
                    try {
                        stats.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return map;
    }

    /** 无使用情况权限时的兜底：只管本次开机以来的累计流量 */
    private static Map<Integer, long[]> fallbackByUid(Context ctx) {
        Map<Integer, long[]> map = new HashMap<>();
        PackageManager pm = ctx.getPackageManager();
        List<ApplicationInfo> apps;
        try {
            apps = pm.getInstalledApplications(0);
        } catch (Throwable t) {
            return map;
        }
        for (ApplicationInfo ai : apps) {
            if (ai == null) continue;
            long rx = TrafficStats.getUidRxBytes(ai.uid);
            long tx = TrafficStats.getUidTxBytes(ai.uid);
            if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED) continue;
            if (rx <= 0 && tx <= 0) continue;
            long[] v = map.get(ai.uid);
            if (v == null) {
                v = new long[2];
                map.put(ai.uid, v);
            }
            v[0] = Math.max(v[0], rx);
            v[1] = Math.max(v[1], tx);
        }
        return map;
    }
}
