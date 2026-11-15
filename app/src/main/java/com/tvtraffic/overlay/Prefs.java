package com.tvtraffic.overlay;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 全局配置。主界面写入，悬浮窗服务读取（每次刷新时重新读取，改完立即生效）。
 */
public final class Prefs {

    public static final String NAME = "tv_traffic_prefs";

    /** 自由位置（拖动过），其余为四角预设 */
    public static final int CORNER_FREE = -1;
    public static final int CORNER_TL = 0;
    public static final int CORNER_TR = 1;
    public static final int CORNER_BR = 2;
    public static final int CORNER_BL = 3;

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public boolean enabled() {
        return sp.getBoolean("enabled", false);
    }

    public void setEnabled(boolean v) {
        sp.edit().putBoolean("enabled", v).apply();
    }

    /** -1=自由位置 0=左上 1=右上 2=右下 3=左下 */
    public int corner() {
        int v = sp.getInt("corner", CORNER_TR);
        if (v < CORNER_FREE || v > 3) v = CORNER_TR;
        return v;
    }

    public void setCorner(int v) {
        if (v < 0) v = CORNER_FREE;
        else v = v % 4;
        sp.edit().putInt("corner", v).apply();
    }

    /** 自由位置以屏幕宽高的比例保存，换分辨率也保持相对位置 */
    public float posXRatio() {
        return sp.getFloat("pos_x_ratio", -1f);
    }

    public float posYRatio() {
        return sp.getFloat("pos_y_ratio", -1f);
    }

    public void setFreePosition(float xr, float yr) {
        float x = Math.max(0f, Math.min(1f, xr));
        float y = Math.max(0f, Math.min(1f, yr));
        sp.edit()
                .putFloat("pos_x_ratio", x)
                .putFloat("pos_y_ratio", y)
                .putInt("corner", CORNER_FREE)
                .apply();
    }

    public int colorDown() {
        return sp.getInt("color_down", 0xFF4FC3F7);
    }

    public void setColorDown(int c) {
        sp.edit().putInt("color_down", c | 0xFF000000).apply();
    }

    public int colorUp() {
        return sp.getInt("color_up", 0xFFFFB74D);
    }

    public void setColorUp(int c) {
        sp.edit().putInt("color_up", c | 0xFF000000).apply();
    }

    public int textSizeSp() {
        return sp.getInt("text_size", 20);
    }

    public void setTextSizeSp(int v) {
        sp.edit().putInt("text_size", Math.max(9, Math.min(40, v))).apply();
    }

    public boolean showBackground() {
        return sp.getBoolean("show_bg", false);
    }

    public void setShowBackground(boolean v) {
        sp.edit().putBoolean("show_bg", v).apply();
    }

    public boolean locked() {
        return sp.getBoolean("locked", false);
    }

    public void setLocked(boolean v) {
        sp.edit().putBoolean("locked", v).apply();
    }

    public boolean showUp() {
        return sp.getBoolean("show_up", true);
    }

    public void setShowUp(boolean v) {
        sp.edit().putBoolean("show_up", v).apply();
    }

    public boolean showDown() {
        return sp.getBoolean("show_down", true);
    }

    public void setShowDown(boolean v) {
        sp.edit().putBoolean("show_down", v).apply();
    }

    /** 位置微调步长，点一下换一档 */
    private static final int[] STEPS = {2, 8, 24, 48, 120};

    public int stepDp() {
        int v = sp.getInt("step_dp", 8);
        for (int s : STEPS) {
            if (s == v) return v;
        }
        return 8;
    }

    public void setStepDp(int v) {
        sp.edit().putInt("step_dp", v).apply();
    }

    public int nextStepDp() {
        int cur = stepDp();
        int idx = 0;
        for (int i = 0; i < STEPS.length; i++) {
            if (STEPS[i] == cur) idx = i;
        }
        int next = STEPS[(idx + 1) % STEPS.length];
        setStepDp(next);
        return next;
    }

    /** 开机自启（默认开：只要上次退出时悬浮窗是开着的，开机就自动恢复） */
    public boolean autoStart() {
        return sp.getBoolean("auto_start", true);
    }

    public void setAutoStart(boolean v) {
        sp.edit().putBoolean("auto_start", v).apply();
    }

    /**
     * 设置页「悬浮窗位置」区按钮的自定义排列，值为按钮 tag 以逗号拼接的顺序。
     * 每行单独保存，序列长度或内容对不上时由界面回落到默认顺序。
     */
    public String buttonOrder(String row) {
        return sp.getString("order_" + row, "");
    }

    public void setButtonOrder(String row, String order) {
        sp.edit().putString("order_" + row, order).apply();
    }

    // ------------------------------------------------------------ 排行榜视图偏好

    /** 0=流量降 1=流量升 2=名称 A→Z 3=名称 Z→A */
    public int usageSort() {
        int v = sp.getInt("usage_sort", 0);
        return (v < 0 || v > 3) ? 0 : v;
    }

    public void setUsageSort(int v) {
        sp.edit().putInt("usage_sort", Math.max(0, Math.min(3, v))).apply();
    }
}
