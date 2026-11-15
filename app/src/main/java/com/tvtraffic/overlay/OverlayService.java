package com.tvtraffic.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;


/**
 * 前台服务：常驻悬浮窗，每秒刷新一次上下行实时速率。
 *
 * 位置支持两种模式：
 *  - 四角预设（gravity 对齐，窗口宽度变化也能贴边）
 *  - 自由位置（拖动过、或用设置页「左移 / 右移 / 上移 / 下移」微调过），以屏幕比例保存
 *
 * 悬浮窗始终 FLAG_NOT_FOCUSABLE，不接管遥控器，也不影响任何下层应用的按键。
 *
 * 关于边界：窗口带 FLAG_LAYOUT_IN_SCREEN，lp.x / lp.y 是**整块屏幕**坐标，
 * 所以必须用真实屏幕尺寸（getMaximumWindowMetrics / getRealMetrics）来夹取，
 * 不能用 getResources().getDisplayMetrics()——后者是应用可用区域，
 * 扣掉了系统装饰，会导致右下角永远差一截、到不了屏幕边缘。
 */
public class OverlayService extends Service {

    public static final String ACTION_START = "com.tvtraffic.overlay.START";
    public static final String ACTION_STOP = "com.tvtraffic.overlay.STOP";

    private static final String CHANNEL_ID = "overlay_channel";
    private static final int NOTI_ID = 1001;
    private static final String EXTRA_DELAY = "delay_start";

    public static volatile boolean running = false;

    /** 供设置页的位置微调直接驱动当前正在运行的悬浮窗 */
    private static OverlayService sInstance;

    private WindowManager wm;
    private LinearLayout container;
    private LinearLayout row;
    private WindowManager.LayoutParams lp;
    private TextView tvDown;
    private TextView tvUp;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TrafficSampler sampler = new TrafficSampler();
    private Prefs prefs;

    private int lastCorner = -999;
    private int lastTextSize = -1;
    private boolean lastShowBg = false;
    private int lastColorDown = 0;
    private int lastColorUp = 0;

    /** 真实屏幕尺寸（供 lp 坐标夹取），配置变化时刷新 */
    private int screenW = 0;
    private int screenH = 0;

    /** 自由位置的内存态比例，避免每帧读写 SharedPreferences 抖动 */
    private float freeXR = -1f;
    private float freeYR = -1f;

    // 拖动状态
    private int touchSlop = 12;
    private float downRawX, downRawY;
    private int downX, downY;
    private boolean dragging;

    private final Runnable startOverlayTask = new Runnable() {
        @Override
        public void run() {
            if (running) return;
            if (!Settings.canDrawOverlays(OverlayService.this)) {
                prefs.setEnabled(false);
                stopSelf();
                return;
            }
            if (!addOverlay()) {
                prefs.setEnabled(false);
                stopSelf();
                return;
            }
            running = true;
            prefs.setEnabled(true);
            sampler.reset();
            handler.postDelayed(ticker, 1000L);
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            updateRates();
            handler.postDelayed(this, 1000L);
        }
    };

    // ---------------------------------------------------------------- 生命周期

    public static void start(Context ctx) {
        start(ctx, false);
    }

    /** @param delayAfterBoot 开机后延迟拉起，等系统窗口服务就绪 */
    public static void start(Context ctx, boolean delayAfterBoot) {
        Intent i = new Intent(ctx, OverlayService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_DELAY, delayAfterBoot);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        if (!running) return;
        Intent i = new Intent(ctx, OverlayService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    // ---------------------------------------------------------------- 设置页联动

    /** 设置页方向微调，返回 false 表示悬浮窗没在运行 */
    public static boolean nudgeByDp(int dxDp, int dyDp) {
        final OverlayService s = sInstance;
        if (s == null || s.container == null) return false;
        s.handler.post(new Runnable() {
            @Override
            public void run() {
                s.moveBy(s.dp(dxDp), s.dp(dyDp));
            }
        });
        return true;
    }

    /** 设置页「居中」，返回 false 表示悬浮窗没在运行 */
    public static boolean moveToCenter() {
        final OverlayService s = sInstance;
        if (s == null || s.container == null) return false;
        s.handler.post(new Runnable() {
            @Override
            public void run() {
                s.centerOnScreen();
            }
        });
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        prefs = new Prefs(this);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        refreshScreenSize();
        startAsForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            prefs.setEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!Settings.canDrawOverlays(this)) {
            prefs.setEnabled(false);
            stopSelf();
            return START_NOT_STICKY;
        }

        boolean delayed = intent != null && intent.getBooleanExtra(EXTRA_DELAY, false);
        if (!running) {
            if (delayed) {
                handler.removeCallbacks(startOverlayTask);
                handler.postDelayed(startOverlayTask, 8000L);
            } else {
                startOverlayTask.run();
                if (!running) return START_NOT_STICKY;
            }
        } else {
            updateRates();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (sInstance == this) sInstance = null;
        if (container != null && wm != null) {
            try {
                wm.removeView(container);
            } catch (Throwable ignored) {
            }
            container = null;
        }
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshScreenSize();
        forceRefresh();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- 屏幕尺寸

    /**
     * 取整块屏幕的真实像素尺寸。
     * 悬浮窗是 FLAG_LAYOUT_IN_SCREEN 的 overlay，坐标系就是整个 display，
     * 用应用可用区域去夹取会让右下角差出系统装饰那一截。
     */
    private void refreshScreenSize() {
        DisplayMetrics dm = new DisplayMetrics();
        boolean ok = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            try {
                Rect b = wm.getMaximumWindowMetrics().getBounds();
                if (b.width() > 0 && b.height() > 0) {
                    dm.widthPixels = b.width();
                    dm.heightPixels = b.height();
                    ok = true;
                }
            } catch (Throwable ignored) {
            }
        }

        if (!ok && wm != null) {
            try {
                Display d = wm.getDefaultDisplay();
                if (d != null) {
                    d.getRealMetrics(dm);
                    ok = dm.widthPixels > 0 && dm.heightPixels > 0;
                }
            } catch (Throwable ignored) {
            }
        }

        if (!ok) {
            dm = getResources().getDisplayMetrics();
        }
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
    }

    private int screenWidth() {
        if (screenW <= 0) refreshScreenSize();
        if (screenW <= 0) screenW = getResources().getDisplayMetrics().widthPixels;
        return screenW;
    }

    private int screenHeight() {
        if (screenH <= 0) refreshScreenSize();
        if (screenH <= 0) screenH = getResources().getDisplayMetrics().heightPixels;
        return screenH;
    }

    // ---------------------------------------------------------------- 悬浮窗构建

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private boolean addOverlay() {
        container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        container.setPadding(dp(10), dp(6), dp(10), dp(6));

        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        tvDown = buildTextView();
        tvUp = buildTextView();

        // 上行在左、下行在右
        LinearLayout.LayoutParams upLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams downLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        downLp.leftMargin = dp(14);
        row.addView(tvUp, upLp);
        row.addView(tvDown, downLp);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        container.addView(row, rowLp);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        // 恒定不可聚焦：不接管遥控器，下层应用按键照常可用
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        container.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return handleTouch(event);
            }
        });

        // 字号 / 内边距变化会改变窗口宽高，自由模式下要重新贴边收敛，
        // 否则贴着右边缘的悬浮窗会因变宽而部分跑出屏幕
        container.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                if (container == null || lp == null) return;
                if (prefs.corner() != Prefs.CORNER_FREE) return;
                int nx = clampX(lp.x);
                int ny = clampY(lp.y);
                if (nx != lp.x || ny != lp.y) {
                    lp.x = nx;
                    lp.y = ny;
                    syncFreeRatioFromLp();
                    pushLayout();
                }
            }
        });

        // 恢复上次保存的自由位置
        freeXR = prefs.posXRatio();
        freeYR = prefs.posYRatio();

        refreshScreenSize();
        applyPositionParams();
        applyStyleParams();

        try {
            wm.addView(container, lp);
        } catch (Throwable t) {
            container = null;
            return false;
        }

        // 视图量出宽高后，自由位置需要再夹一次，避免超出屏幕
        container.post(new Runnable() {
            @Override
            public void run() {
                if (container == null) return;
                if (prefs.corner() == Prefs.CORNER_FREE) {
                    applyPositionParams();
                    pushLayout();
                }
            }
        });

        lastCorner = prefs.corner();
        lastTextSize = prefs.textSizeSp();
        lastShowBg = prefs.showBackground();
        updateRates();
        return true;
    }

    private TextView buildTextView() {
        TextView tv = new TextView(this);
        tv.setText("--");
        tv.setTypeface(Fonts.bold(this));
        tv.setIncludeFontPadding(false);
        tv.setSingleLine(true);
        return tv;
    }

    /** 根据配置计算窗口位置（不推送到 WindowManager） */
    private void applyPositionParams() {
        if (lp == null) return;
        int corner = prefs.corner();

        if (corner == Prefs.CORNER_FREE) {
            lp.gravity = Gravity.TOP | Gravity.START;
            float xr = freeXR >= 0f ? freeXR : prefs.posXRatio();
            float yr = freeYR >= 0f ? freeYR : prefs.posYRatio();
            if (xr >= 0f && yr >= 0f) {
                freeXR = xr;
                freeYR = yr;
                lp.x = clampX(Math.round(xr * screenWidth()));
                lp.y = clampY(Math.round(yr * screenHeight()));
            } else {
                // 没有比例记录就沿用当前坐标，绝不回落到某个角落
                lp.x = clampX(lp.x);
                lp.y = clampY(lp.y);
            }
            return;
        }

        switch (corner) {
            case Prefs.CORNER_TL:
                lp.gravity = Gravity.TOP | Gravity.START;
                break;
            case Prefs.CORNER_BL:
                lp.gravity = Gravity.BOTTOM | Gravity.START;
                break;
            case Prefs.CORNER_BR:
                lp.gravity = Gravity.BOTTOM | Gravity.END;
                break;
            case Prefs.CORNER_TR:
            default:
                lp.gravity = Gravity.TOP | Gravity.END;
                break;
        }
        // 四角预设直接贴屏幕边，视觉空隙由容器内边距提供
        lp.x = 0;
        lp.y = 0;
    }

    /** 左边界 0，右边界 = 屏幕宽 - 窗口宽，这样能真正贴到屏幕最右侧 */
    private int clampX(int x) {
        int w = container == null ? 0 : container.getWidth();
        int max = Math.max(0, screenWidth() - w);
        return Math.max(0, Math.min(x, max));
    }

    private int clampY(int y) {
        int h = container == null ? 0 : container.getHeight();
        int max = Math.max(0, screenHeight() - h);
        return Math.max(0, Math.min(y, max));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** 字号 / 颜色 / 背景，变化时才重建 */
    private void applyStyleParams() {
        int size = prefs.textSizeSp();
        boolean showBg = prefs.showBackground();

        tvDown.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        tvUp.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        lastTextSize = size;

        tvDown.setVisibility(prefs.showDown() ? View.VISIBLE : View.GONE);
        tvUp.setVisibility(prefs.showUp() ? View.VISIBLE : View.GONE);

        int colorDown = prefs.colorDown();
        int colorUp = prefs.colorUp();
        tvDown.setTextColor(colorDown);
        tvUp.setTextColor(colorUp);
        tvDown.setShadowLayer(dp(3), 0f, dp(1), shadowFor(colorDown));
        tvUp.setShadowLayer(dp(3), 0f, dp(1), shadowFor(colorUp));
        lastColorDown = colorDown;
        lastColorUp = colorUp;

        lockWidth();

        // 无背景时把内边距收到最小，否则文字永远离屏幕边差 10dp，
        // 看起来就像「挪不到边缘」；有背景时才留出描边需要的空间
        if (showBg) {
            container.setPadding(dp(10), dp(6), dp(10), dp(6));
        } else {
            container.setPadding(dp(2), dp(2), dp(2), dp(2));
        }

        if (showBg) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(0x8C000000);
            g.setCornerRadius(dp(8));
            container.setBackground(g);
        } else {
            container.setBackground(null);
        }
        lastShowBg = showBg;
    }

    /** 深色文字配浅色阴影，浅色文字配深色阴影，任何背景都看得清 */
    private int shadowFor(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        double lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return lum < 0.35 ? 0xE6FFFFFF : 0xE6000000;
    }

    /**
     * MiSans 是比例字体，数字宽度不等宽。
     * 给左右两段文本锁定同一宽度，读数跳动时悬浮窗不会左右抖。
     */
    private void lockWidth() {
        if (tvDown == null || tvUp == null) return;
        int w = (int) Math.ceil(tvDown.getPaint().measureText("↓ 999.9GB/s")) + dp(2);
        LinearLayout.LayoutParams p1 = (LinearLayout.LayoutParams) tvDown.getLayoutParams();
        if (p1 != null && p1.width != w) {
            p1.width = w;
            tvDown.setLayoutParams(p1);
        }
        LinearLayout.LayoutParams p2 = (LinearLayout.LayoutParams) tvUp.getLayoutParams();
        if (p2 != null && p2.width != w) {
            p2.width = w;
            tvUp.setLayoutParams(p2);
        }
        tvDown.setGravity(Gravity.CENTER);
        tvUp.setGravity(Gravity.CENTER);
    }

    private void pushLayout() {
        if (container == null || lp == null) return;
        try {
            wm.updateViewLayout(container, lp);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 拖动

    private boolean handleTouch(MotionEvent event) {
        if (container == null || lp == null) return false;
        if (prefs.locked()) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downX = lp.x;
                downY = lp.y;
                dragging = false;
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                    dragging = true;
                    // 第一次真正拖动才切到自由模式，并以当前手指为新基准，
                    // 避免四角 gravity 换算成绝对坐标时画面跳一下
                    ensureFreePosition();
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    downX = lp.x;
                    downY = lp.y;
                    dx = 0f;
                    dy = 0f;
                }
                if (dragging) {
                    lp.x = clampX(downX + (int) dx);
                    lp.y = clampY(downY + (int) dy);
                    // 只更新内存比例，逐帧写盘会卡
                    syncFreeRatioFromLp();
                    pushLayout();
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
                // 轻点不做任何事：位置只由设置页的四角按钮和方向微调决定
                if (dragging) saveFreePosition();
                dragging = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (dragging) saveFreePosition();
                dragging = false;
                return true;

            default:
                return false;
        }
    }

    // ---------------------------------------------------------------- 位置计算

    /** 从四角模式切到自由模式：把当前实际屏幕坐标取出来继续用 */
    private void ensureFreePosition() {
        if (container == null || lp == null) return;

        if (prefs.corner() == Prefs.CORNER_FREE) {
            // 已是自由模式：内存里没比例就从配置补，再没有就用当前真实坐标兜底，
            // 否则会沿着某个残留的 gravity 跳到屏幕角落
            if (freeXR < 0f || freeYR < 0f) {
                freeXR = prefs.posXRatio();
                freeYR = prefs.posYRatio();
            }
            if (freeXR < 0f || freeYR < 0f) {
                captureCurrentLocation();
            }
            return;
        }

        captureCurrentLocation();
        prefs.setFreePosition(freeXR, freeYR);
        lastCorner = Prefs.CORNER_FREE;
    }

    /** 把窗口此刻在屏幕上的绝对位置取出来，转成 TOP|START 坐标并记录比例 */
    private void captureCurrentLocation() {
        int[] loc = new int[2];
        container.getLocationOnScreen(loc);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = clampX(loc[0]);
        lp.y = clampY(loc[1]);
        syncFreeRatioFromLp();
    }

    private void moveBy(int dx, int dy) {
        if (container == null || lp == null) return;
        ensureFreePosition();
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = clampX(lp.x + dx);
        lp.y = clampY(lp.y + dy);
        syncFreeRatioFromLp();
        pushLayout();
        prefs.setFreePosition(freeXR, freeYR);
        lastCorner = Prefs.CORNER_FREE;
    }

    private void centerOnScreen() {
        if (container == null || lp == null) return;
        int w = container.getWidth();
        int h = container.getHeight();
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Math.max(0, (screenWidth() - w) / 2);
        lp.y = Math.max(0, (screenHeight() - h) / 2);
        syncFreeRatioFromLp();
        prefs.setFreePosition(freeXR, freeYR);
        lastCorner = Prefs.CORNER_FREE;
        pushLayout();
    }

    /** 用当前 lp 的像素坐标换算比例，只写内存 */
    private void syncFreeRatioFromLp() {
        if (lp == null) return;
        int w = screenWidth();
        int h = screenHeight();
        if (w <= 0 || h <= 0) return;
        freeXR = clamp01((float) lp.x / w);
        freeYR = clamp01((float) lp.y / h);
    }

    private void saveFreePosition() {
        if (lp == null) return;
        syncFreeRatioFromLp();
        if (freeXR < 0f || freeYR < 0f) return;
        prefs.setFreePosition(freeXR, freeYR);
        lastCorner = Prefs.CORNER_FREE;
    }

    private void forceRefresh() {
        if (container == null || lp == null) return;
        applyPositionParams();
        applyStyleParams();
        pushLayout();
    }

    /** 主界面改完配置后同步（每秒顺带检查一次） */
    private void syncIfChanged() {
        if (container == null || lp == null) return;

        if (prefs.corner() != lastCorner) {
            lastCorner = prefs.corner();
            if (prefs.corner() == Prefs.CORNER_FREE) {
                freeXR = prefs.posXRatio();
                freeYR = prefs.posYRatio();
            }
            applyPositionParams();
            pushLayout();
        }

        if (prefs.textSizeSp() != lastTextSize
                || prefs.showBackground() != lastShowBg
                || prefs.colorDown() != lastColorDown
                || prefs.colorUp() != lastColorUp) {
            applyStyleParams();
            pushLayout();
        }
    }

    private void updateRates() {
        if (container == null) return;
        syncIfChanged();
        long[] r = sampler.sample();
        tvDown.setText("↓ " + Fmt.speedForOverlay(r[0]));
        tvUp.setText("↑ " + Fmt.speedForOverlay(r[1]));
    }

    // ---------------------------------------------------------------- 前台通知

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            try {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        getString(R.string.noti_channel), NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                ch.setSound(null, null);
                ch.enableVibration(false);
                ch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
                nm.createNotificationChannel(ch);
            } catch (Throwable ignored) {
            }
        }

        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.noti_text))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            b.setPriority(Notification.PRIORITY_MIN);
        }

        try {
            startForeground(NOTI_ID, b.build());
        } catch (Throwable ignored) {
            // 极少数 ROM 对通知有限制，服务本身仍然要在前台跑
        }
    }
}
