package com.tvtraffic.overlay;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    /** 悬浮窗文字可选颜色 */
    private static final int[] PALETTE = {
            0xFFFFFFFF, // 白
            0xFF4FC3F7, // 青
            0xFF82AAFF, // 蓝
            0xFF6EE7A8, // 绿
            0xFFFFD166, // 黄
            0xFFFFB74D, // 橙
            0xFFFF7B72, // 红
            0xFFC792EA  // 紫
    };

    private Prefs prefs;
    private TextView tvOverlayPerm;
    private TextView tvUsagePerm;
    private TextView tvHint;
    private Button btnToggle;
    private Button btnBg;
    private Button btnLock;
    private Button btnAutoStart;
    private Button btnStep;

    /** 位置区两行容器：按钮顺序可由用户长按 OK 键自定义 */
    private static final String ORDER_CORNERS = "corners";
    private static final String ORDER_NUDGE = "nudge";
    private LinearLayout rowCorners;
    private LinearLayout rowNudge;
    /** 正在调整位置的按钮，null 表示不在排列模式 */
    private Button arrangeBtn;
    /** 进入排列模式时的顺序快照，取消时按它还原 */
    private List<String> arrangeSnapshot;

    /** 遥控器长按判定：系统只给 DPAD 发重复事件，不会派发 long click，只能自己计时 */
    private static final long LONG_PRESS_MS = 600L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Button pressBtn;
    private Runnable pressAction;
    private boolean longPressFired;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Fonts.apply(this, getWindow().getDecorView());
        prefs = new Prefs(this);

        tvOverlayPerm = findViewById(R.id.tv_perm_overlay);
        tvUsagePerm = findViewById(R.id.tv_perm_usage);
        tvHint = findViewById(R.id.tv_hint);
        btnToggle = findViewById(R.id.btn_toggle);
        btnBg = findViewById(R.id.btn_bg);
        btnLock = findViewById(R.id.btn_lock);
        btnAutoStart = findViewById(R.id.btn_autostart);
        btnStep = findViewById(R.id.btn_step);

        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleOverlay();
            }
        });

        findViewById(R.id.btn_grant_overlay).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openOverlaySettings();
            }
        });

        findViewById(R.id.btn_grant_usage).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUsageSettings();
            }
        });

        setupPositionRows();

        installPositionButton((Button) findViewById(R.id.btn_tl), () -> setCorner(Prefs.CORNER_TL));
        installPositionButton((Button) findViewById(R.id.btn_tr), () -> setCorner(Prefs.CORNER_TR));
        installPositionButton((Button) findViewById(R.id.btn_br), () -> setCorner(Prefs.CORNER_BR));
        installPositionButton((Button) findViewById(R.id.btn_bl), () -> setCorner(Prefs.CORNER_BL));
        // 遥控器/鼠标：点一下走一步；按住 OK 键 0.6 秒进入按钮排列模式
        installPositionButton((Button) findViewById(R.id.btn_left), () -> nudge(-1, 0));
        installPositionButton((Button) findViewById(R.id.btn_right), () -> nudge(1, 0));
        installPositionButton((Button) findViewById(R.id.btn_up), () -> nudge(0, -1));
        installPositionButton((Button) findViewById(R.id.btn_down), () -> nudge(0, 1));
        installPositionButton((Button) findViewById(R.id.btn_center), () -> {
            if (!OverlayService.moveToCenter()) toast("请先开启悬浮窗");
            updateUi();
        });
        installPositionButton((Button) findViewById(R.id.btn_step), () -> {
            prefs.nextStepDp();
            updateUi();
        });

        findViewById(R.id.btn_size_minus).setOnClickListener(v -> {
            prefs.setTextSizeSp(prefs.textSizeSp() - 1);
            updateUi();
        });
        findViewById(R.id.btn_size_plus).setOnClickListener(v -> {
            prefs.setTextSizeSp(prefs.textSizeSp() + 1);
            updateUi();
        });

        btnBg.setOnClickListener(v -> {
            prefs.setShowBackground(!prefs.showBackground());
            updateUi();
        });
        btnLock.setOnClickListener(v -> {
            prefs.setLocked(!prefs.locked());
            updateUi();
        });
        btnAutoStart.setOnClickListener(v -> {
            prefs.setAutoStart(!prefs.autoStart());
            updateUi();
        });

        findViewById(R.id.btn_usage).setOnClickListener(v -> {
            if (!hasUsageAccess()) {
                toast("请先授予「应用流量统计」权限");
                return;
            }
            startActivity(new Intent(this, AppUsageActivity.class));
        });

        buildPalette(findViewById(R.id.palette_down), true);
        buildPalette(findViewById(R.id.palette_up), false);

        tvHint.setText(adbHint());
    }

    /** 底部文字刷新任务，连发时只跑最后一次，避免重复重建色板 */
    private final Runnable refreshHintTask = new Runnable() {
        @Override
        public void run() {
            updateHint();
        }
    };

    private long lastNudgeToastAt = 0L;

    private void nudge(int dirX, int dirY) {
        int step = prefs.stepDp();
        if (!OverlayService.nudgeByDp(dirX * step, dirY * step)) {
            long now = System.currentTimeMillis();
            if (now - lastNudgeToastAt > 1500L) {
                lastNudgeToastAt = now;
                toast("请先开启悬浮窗");
            }
            return;
        }
        scheduleHintRefresh();
    }

    private void scheduleHintRefresh() {
        if (tvHint == null) return;
        tvHint.removeCallbacks(refreshHintTask);
        tvHint.postDelayed(refreshHintTask, 140);
    }

    /**
     * 位置区按钮的统一装配：触摸点击、触摸长按、遥控器点按与长按（系统不会给 DPAD 派发
     * long click，所以按键这边靠 repeatCount 与自建计时器判定）。
     */
    private void installPositionButton(Button b, final Runnable action) {
        if (b == null) return;
        b.setOnClickListener(v -> action.run());
        b.setOnLongClickListener(v -> {
            enterArrange(b);
            return true;
        });
        b.setOnKeyListener((v, keyCode, event) -> handleKey(b, action, keyCode, event));
    }

    private boolean handleKey(Button b, Runnable action, int keyCode, KeyEvent event) {
        if (b == arrangeBtn) {
            return handleArrangeKey(b, keyCode, event);
        }
        if (!isConfirmKey(keyCode)) return false;

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                pressBtn = b;
                pressAction = action;
                longPressFired = false;
                handler.removeCallbacks(longPressTask);
                handler.postDelayed(longPressTask, LONG_PRESS_MS);
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(longPressTask);
            if (!longPressFired && b == pressBtn && pressAction != null) {
                pressAction.run();
            }
            pressBtn = null;
            pressAction = null;
            longPressFired = false;
            return true;
        }
        return false;
    }

    private static boolean isConfirmKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /** 按住 OK 超过阈值即进入排列模式；松手前不执行原动作 */
    private final Runnable longPressTask = new Runnable() {
        @Override
        public void run() {
            final Button b = pressBtn;
            // 期间焦点被别的控件拿走（例如鼠标点了别处）就不算长按
            if (b == null || !b.isFocused()) {
                pressBtn = null;
                pressAction = null;
                return;
            }
            longPressFired = true;
            pressBtn = null;
            pressAction = null;
            enterArrange(b);
        }
    };

    // ------------------------------------------------------------ 位置区按钮自定义排列

    /** 恢复保存的按钮顺序 */
    private void setupPositionRows() {
        rowCorners = findViewById(R.id.row_corners);
        rowNudge = findViewById(R.id.row_nudge);
        restoreOrder(rowCorners, ORDER_CORNERS, null);
        restoreOrder(rowNudge, ORDER_NUDGE, null);
    }

    /** 给行内按钮打 tag（用初始 index），顺序被打乱后仍能识别是谁 */
    private Map<String, View> tagChildren(LinearLayout row) {
        Map<String, View> map = new HashMap<>();
        int n = row.getChildCount();
        for (int i = 0; i < n; i++) {
            View v = row.getChildAt(i);
            Object tag = v.getTag();
            if (!(tag instanceof String) || ((String) tag).length() == 0) {
                v.setTag("b" + i);
            }
            map.put((String) v.getTag(), v);
        }
        return map;
    }

    /**
     * 按 order 重排一行；order 为 null 表示读配置，读不到或对不上就用当前顺序（即默认顺序）。
     */
    private void restoreOrder(LinearLayout row, String prefKey, List<String> order) {
        if (row == null) return;
        Map<String, View> map = tagChildren(row);
        if (order == null) {
            order = parseOrder(prefs.buttonOrder(prefKey));
            if (order.size() != map.size() || !map.keySet().containsAll(order)) {
                order = null;
            }
        }
        if (order == null) return;
        applyOrder(row, map, order);
    }

    private List<String> parseOrder(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null || raw.length() == 0) return list;
        for (String s : raw.split(",")) {
            if (s != null && s.length() > 0) list.add(s);
        }
        return list;
    }

    /** 按给定 key 顺序把 View 重新放回容器，保留各自 LayoutParams */
    private void applyOrder(LinearLayout row, Map<String, View> map, List<String> order) {
        Map<String, LinearLayout.LayoutParams> lps = new HashMap<>();
        for (Map.Entry<String, View> e : map.entrySet()) {
            lps.put(e.getKey(), (LinearLayout.LayoutParams) e.getValue().getLayoutParams());
        }
        row.removeAllViews();
        for (String key : order) {
            View v = map.get(key);
            LinearLayout.LayoutParams lp = lps.get(key);
            if (v == null || lp == null) continue;
            row.addView(v, lp);
        }
        fixRowEndMargin(row);
    }

    /** 行内最后一个按钮不留右边距，否则整行看着偏左 */
    private void fixRowEndMargin(LinearLayout row) {
        int n = row.getChildCount();
        for (int i = 0; i < n; i++) {
            View v = row.getChildAt(i);
            if (!(v.getLayoutParams() instanceof LinearLayout.LayoutParams)) continue;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
            lp.rightMargin = (i == n - 1) ? 0 : dp(12);
            v.setLayoutParams(lp);
        }
    }

    private List<String> currentOrder(LinearLayout row) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) {
            Object tag = row.getChildAt(i).getTag();
            if (tag instanceof String) list.add((String) tag);
        }
        return list;
    }

    /** 长按 OK：把该按钮标记为「正在调整」，左右键与相邻按钮交换 */
    private void enterArrange(Button b) {
        if (arrangeBtn != null) finishArrange(true);
        final LinearLayout row = (LinearLayout) b.getParent();
        if (row == null) return;
        handler.removeCallbacks(longPressTask);
        arrangeBtn = b;
        arrangeSnapshot = currentOrder(row);
        b.setPressed(false);
        b.setBackgroundResource(R.drawable.bg_arrange);
        b.requestFocus();
        updateHint();
        toast("左右键调整位置，OK 完成，返回键取消");
    }

    /** 排列模式下的按键：左右换位、OK 保存、返回取消，上下与其它键一律吞掉 */
    private boolean handleArrangeKey(Button b, int keyCode, KeyEvent event) {
        // 抬起事件也吞掉，避免松手时把按钮当普通点击执行一次
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                swapWithNeighbor(b, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                swapWithNeighbor(b, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // 排列只在当前行内进行，上下键不做任何事，也不让焦点跑掉
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // 只认首次按下：长按进入排列时按键还按着，重复事件不能当成「完成」
                if (event.getRepeatCount() == 0) finishArrange(true);
                return true;
            default:
                return false;
        }
    }

    /** 与左/右相邻按钮互换位置；到头就停住 */
    private void swapWithNeighbor(Button b, int delta) {
        if (b == null || !(b.getParent() instanceof LinearLayout)) return;
        final LinearLayout row = (LinearLayout) b.getParent();
        final int idx = row.indexOfChild(b);
        final int to = idx + delta;
        if (idx < 0 || to < 0 || to >= row.getChildCount()) return;

        final int n = row.getChildCount();
        List<View> views = new ArrayList<>(n);
        List<LinearLayout.LayoutParams> lps = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            View v = row.getChildAt(i);
            views.add(v);
            lps.add((LinearLayout.LayoutParams) v.getLayoutParams());
        }
        // 视图和它自己的 LayoutParams 一起换位，避免行尾间距跟着错位
        View tmpView = views.set(idx, views.get(to));
        views.set(to, tmpView);
        LinearLayout.LayoutParams tmpLp = lps.set(idx, lps.get(to));
        lps.set(to, tmpLp);

        row.removeAllViews();
        for (int i = 0; i < n; i++) {
            row.addView(views.get(i), lps.get(i));
        }
        fixRowEndMargin(row);
        b.requestFocus();
    }

    private void finishArrange(boolean save) {
        final Button b = arrangeBtn;
        if (b == null) return;
        arrangeBtn = null;
        handler.removeCallbacks(longPressTask);
        pressBtn = null;
        pressAction = null;
        longPressFired = false;
        b.setBackgroundResource(R.drawable.bg_btn);
        LinearLayout row = (b.getParent() instanceof LinearLayout) ? (LinearLayout) b.getParent() : null;
        if (row != null) {
            if (save) {
                prefs.setButtonOrder(rowKey(row), join(currentOrder(row)));
            } else if (arrangeSnapshot != null) {
                restoreOrder(row, rowKey(row), arrangeSnapshot);
            }
        }
        arrangeSnapshot = null;
        updateHint();
        b.requestFocus();
    }

    private String rowKey(LinearLayout row) {
        return row == rowCorners ? ORDER_CORNERS : ORDER_NUDGE;
    }

    private String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        return sb.toString();
    }

    @Override
    public void onBackPressed() {
        if (arrangeBtn != null) {
            finishArrange(false);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(longPressTask);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUi();
    }

    // ------------------------------------------------------------ 操作

    private void toggleOverlay() {
        if (OverlayService.running || prefs.enabled()) {
            OverlayService.stop(this);
            prefs.setEnabled(false);
            toast("已关闭悬浮窗");
            btnToggle.postDelayed(this::updateUi, 400);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("缺少悬浮窗权限，请先授予");
            openOverlaySettings();
            return;
        }
        OverlayService.start(this);
        prefs.setEnabled(true);
        toast("悬浮窗已开启");
        btnToggle.postDelayed(this::updateUi, 400);
    }

    private void setCorner(int corner) {
        prefs.setCorner(corner);
        updateUi();
    }

    private void openOverlaySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
            toast("请打开「允许显示在其他应用上层」");
        } catch (Exception e) {
            toast("本机没有该设置页，请用 adb 授权（见下方说明）");
        }
    }

    private void openUsageSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            toast("请找到本应用并允许访问使用记录");
        } catch (Exception e) {
            toast("本机没有该设置页，请用 adb 授权（见下方说明）");
        }
    }

    // ------------------------------------------------------------ 状态

    private boolean hasUsageAccess() {
        try {
            AppOpsManager aom = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), getPackageName());
            } else {
                mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void updateUi() {
        boolean overlayOk = Settings.canDrawOverlays(this);
        boolean usageOk = hasUsageAccess();

        tvOverlayPerm.setText("悬浮窗权限：" + (overlayOk ? "已授权" : "未授权"));
        tvOverlayPerm.setTextColor(overlayOk ? 0xFF6EE7A8 : 0xFFFF7B72);

        tvUsagePerm.setText("应用流量统计权限：" + (usageOk ? "已授权" : "未授权"));
        tvUsagePerm.setTextColor(usageOk ? 0xFF6EE7A8 : 0xFFFF7B72);

        boolean on = OverlayService.running || prefs.enabled();
        btnToggle.setText(on ? "关闭悬浮窗" : "开启悬浮窗");

        btnBg.setText(prefs.showBackground() ? "背景：半透明" : "背景：透明");
        btnLock.setText(prefs.locked() ? "锁定位置：开" : "锁定位置：关");
        btnAutoStart.setText(prefs.autoStart() ? "开机自启：开" : "开机自启：关");
        btnStep.setText("步长：" + prefs.stepDp() + "dp");

        updateHint();
    }

    /** 只刷新底部说明文字，微调连发时不重建色板，避免卡顿 */
    private void updateHint() {
        tvHint.setTextSize(14);
        if (arrangeBtn != null) {
            tvHint.setText("排列模式：「" + arrangeBtn.getText() + "」正在调整\n"
                    + "左右方向键与相邻按钮交换位置，按 OK 完成并保存，按返回键取消。\n"
                    + "按钮只在它所在的那一行里移动。");
            return;
        }
        String[] names = {"左上", "右上", "右下", "左下"};
        String posText = prefs.corner() < 0 ? "自由位置" : names[prefs.corner()];
        tvHint.setText(adbHint() + "\n\n当前位置：" + posText
                + "　字号：" + prefs.textSizeSp() + "sp"
                + "\n用「左移 / 右移 / 上移 / 下移」把悬浮窗摆到屏幕任意位置；"
                + "点「步长」在 2 / 8 / 24 / 48 / 120dp 之间切换，「居中」一键回到屏幕中央。"
                + "\n长按任一位置按钮的 OK 键，可以左右调整按钮排列。"
                + "\n\n悬浮窗不接管遥控器，不影响任何应用的按键操作。"
                + "\n字体：MiSans（小米开源字体，免费商用）");
    }

    // ------------------------------------------------------------ 颜色色板

    private void buildPalette(LinearLayout row, final boolean isDown) {
        row.removeAllViews();
        int selected = isDown ? prefs.colorDown() : prefs.colorUp();
        int size = dp(34);
        for (final int color : PALETTE) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.rightMargin = dp(12);
            dot.setLayoutParams(lp);
            dot.setTag(color);
            dot.setFocusable(true);
            dot.setClickable(true);
            dot.setBackground(dotBackground(color, color == selected));
            dot.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (isDown) prefs.setColorDown(color);
                    else prefs.setColorUp(color);
                    refreshPalette(row, color);
                }
            });
            row.addView(dot);
        }
    }

    private void refreshPalette(LinearLayout row, int selected) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            int color = (Integer) v.getTag();
            v.setBackground(dotBackground(color, color == selected));
        }
    }

    private Drawable dotBackground(int color, boolean selected) {
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_focused}, circle(color, 0xFFFFFFFF, dp(3)));
        sld.addState(new int[]{}, circle(color,
                selected ? 0xFFFFFFFF : 0x40FFFFFF,
                selected ? dp(3) : dp(1)));
        return sld;
    }

    private GradientDrawable circle(int color, int strokeColor, int strokeWidth) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(Math.max(1, strokeWidth), strokeColor);
        return g;
    }

    private int dp(float v) {
        return Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private String adbHint() {
        String pkg = getPackageName();
        return "电视/盒子通常没有「显示在其他应用上层」和「使用情况访问」的设置入口，\n"
                + "用同一局域网的电脑执行下面两条命令即可（需先开启 ADB 调试）：\n\n"
                + "adb connect 电视IP:5555\n"
                + "adb shell appops set " + pkg + " SYSTEM_ALERT_WINDOW allow\n"
                + "adb shell appops set " + pkg + " GET_USAGE_STATS allow\n\n"
                + "授予后回到本页，状态会变成「已授权」。";
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
