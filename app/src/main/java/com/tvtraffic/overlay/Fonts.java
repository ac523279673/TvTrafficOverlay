package com.tvtraffic.overlay;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * 统一字体：MiSans（小米开源字体）。
 * 字体文件已做子集化处理，只保留中文常用字 + 拉丁字符 + 常用符号，减小 APK 体积。
 */
public final class Fonts {

    private static final String ASSET_REGULAR = "fonts/MiSans-Regular.ttf";
    private static final String ASSET_BOLD = "fonts/MiSans-DemiBold.ttf";

    private static Typeface sRegular;
    private static Typeface sBold;

    private Fonts() {
    }

    public static Typeface regular(Context ctx) {
        if (sRegular == null) {
            try {
                sRegular = Typeface.createFromAsset(ctx.getAssets(), ASSET_REGULAR);
            } catch (Throwable t) {
                sRegular = Typeface.SANS_SERIF;
            }
        }
        return sRegular;
    }

    public static Typeface bold(Context ctx) {
        if (sBold == null) {
            try {
                sBold = Typeface.createFromAsset(ctx.getAssets(), ASSET_BOLD);
            } catch (Throwable t) {
                sBold = Typeface.create(regular(ctx), Typeface.BOLD);
            }
        }
        return sBold;
    }

    /** 递归把字体应用到整棵视图树，粗体节点用 DemiBold 字重 */
    public static void apply(Context ctx, View root) {
        if (root == null) return;
        if (root instanceof TextView) {
            TextView tv = (TextView) root;
            Typeface tf = tv.getTypeface();
            boolean isBold = tf != null && tf.isBold();
            tv.setTypeface(isBold ? bold(ctx) : regular(ctx));
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                apply(ctx, group.getChildAt(i));
            }
        }
    }
}
