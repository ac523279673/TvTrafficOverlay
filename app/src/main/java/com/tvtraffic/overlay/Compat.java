package com.tvtraffic.overlay;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.provider.Settings;
import android.view.View;

/**
 * 低版本兼容封装。
 *
 * 目标最低版本是 Android 4.0（API 14），那个年代还是 Dalvik 虚拟机，
 * 类里只要出现「当前系统没有的类」，整个方法可能直接 VerifyError，
 * 所以凡是 API 19+ 才有的类（AppOpsManager / NetworkStatsManager / NotificationChannel）
 * 都单独放一个类，只在版本够的时候加载，见 {@link UsageAccess}、{@link NetworkStatsQuery}、{@link NotiCompat}。
 */
public final class Compat {

    private Compat() {
    }

    /** Android 6.0 起悬浮窗要单独授权；更早的版本在安装时就已授予 */
    public static boolean canDrawOverlays(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        try {
            return Settings.canDrawOverlays(ctx);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * View.setBackground 是 API 16 才有的，4.0/4.1 上会 NoSuchMethodError。
     * setBackgroundDrawable 虽然已废弃，但从 API 1 到最新都有效，行为一致。
     */
    @SuppressWarnings("deprecation")
    public static void setBg(View v, Drawable d) {
        if (v == null) return;
        v.setBackgroundDrawable(d);
    }

    /** Long.compare 是 API 19 才有的 */
    public static int cmp(long a, long b) {
        if (a < b) return -1;
        return a == b ? 0 : 1;
    }

    /** 「使用情况访问」与 NetworkStatsManager 都要 Android 6.0+ */
    public static boolean hasUsageStatsApi() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    /** Android 4.0~4.4：没有 NetworkStatsManager，只能看本次开机累计 */
    public static boolean isLegacyUsage() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M;
    }
}
