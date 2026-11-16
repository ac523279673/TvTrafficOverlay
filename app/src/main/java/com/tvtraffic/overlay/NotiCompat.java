package com.tvtraffic.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/**
 * Android 8.0+ 的通知渠道。
 *
 * NotificationChannel 是 API 26 才有的类，放在独立的类里，
 * 低版本只走 {@link #ensure} 开头的版本判断，永远不会加载 {@link #create}。
 */
final class NotiCompat {

    private NotiCompat() {
    }

    static void ensure(Context ctx, String id, String name) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        create(ctx, id, name);
    }

    private static void create(Context ctx, String id, String name) {
        // 再判一次：让 lint 与后来改代码的人都能一眼看出这里只在 8.0+ 运行
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            NotificationChannel ch = new NotificationChannel(id, name,
                    NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {
        }
    }
}
