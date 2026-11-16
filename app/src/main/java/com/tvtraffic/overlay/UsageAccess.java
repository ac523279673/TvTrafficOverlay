package com.tvtraffic.overlay;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;

/**
 * 检查「使用情况访问」权限。
 *
 * AppOpsManager 是 API 19 才有的类，所以整段逻辑必须和 MainActivity 隔离：
 * Android 4.0~4.4 是 Dalvik，类里引用了不存在的类型会让方法校验失败，
 * 只要不在低版本加载这个类就没事。
 */
final class UsageAccess {

    private UsageAccess() {
    }

    static boolean granted(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        try {
            AppOpsManager aom = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) return false;
            int mode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mode = aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), ctx.getPackageName());
            } else {
                mode = aom.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), ctx.getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }
}
