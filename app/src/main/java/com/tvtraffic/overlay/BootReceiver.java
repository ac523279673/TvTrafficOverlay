package com.tvtraffic.overlay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/**
 * 开机自启：开机完成后（或本应用升级后）自动恢复悬浮窗。
 * 前提是上次关闭前悬浮窗是开着的，且「开机自启」开关没有被关掉。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Prefs prefs = new Prefs(context);
        if (!prefs.autoStart() || !prefs.enabled()) return;
        if (!Settings.canDrawOverlays(context)) return;

        String action = intent == null || intent.getAction() == null ? "" : intent.getAction();
        boolean afterBoot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);

        try {
            // 开机场景延后几秒拉起，等 Launcher 和窗口服务就绪，避免 addView 失败
            OverlayService.start(context, afterBoot);
        } catch (Throwable ignored) {
            // 部分电视会拦截后台启动前台服务，静默失败即可
        }
    }
}
