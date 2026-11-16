package com.tvtraffic.overlay;

import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;

import java.util.HashMap;
import java.util.Map;

/**
 * 用 NetworkStatsManager 按 uid 汇总流量（需要「使用情况访问」权限，可跨重启）。
 *
 * NetworkStats / NetworkStatsManager 是 API 23 才有的类，单独成类是为了
 * 让 Android 4.0~4.4（Dalvik）根本不加载它。调用方必须先判断版本。
 */
final class NetworkStatsQuery {

    private NetworkStatsQuery() {
    }

    static Map<Integer, long[]> query(Context ctx, long start, long end) {
        Map<Integer, long[]> map = new HashMap<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return map;

        NetworkStatsManager nsm = (NetworkStatsManager)
                ctx.getSystemService(Context.NETWORK_STATS_SERVICE);
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
}
