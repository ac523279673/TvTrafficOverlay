package com.tvtraffic.overlay;

import java.util.Locale;

/** 速率 / 流量格式化 */
public final class Fmt {

    private Fmt() {
    }

    /** 1234567 -> "1.23 MB/s" */
    public static String speed(long bytesPerSec) {
        if (bytesPerSec < 0) return "--";
        double v = bytesPerSec;
        if (v < 1024) {
            return String.format(Locale.US, "%d B/s", (long) v);
        } else if (v < 1024 * 1024) {
            double k = v / 1024.0;
            return k < 10 ? String.format(Locale.US, "%.2f KB/s", k)
                    : String.format(Locale.US, "%.1f KB/s", k);
        } else if (v < 1024L * 1024 * 1024) {
            double m = v / (1024.0 * 1024.0);
            return m < 10 ? String.format(Locale.US, "%.2f MB/s", m)
                    : String.format(Locale.US, "%.1f MB/s", m);
        } else {
            return String.format(Locale.US, "%.2f GB/s", v / (1024.0 * 1024.0 * 1024.0));
        }
    }

    /** 累计流量 1234567 -> "1.18 MB" */
    public static String volume(long bytes) {
        if (bytes < 0) return "--";
        if (bytes < 1024) return bytes + " B";
        double k = bytes / 1024.0;
        if (k < 1024) return String.format(Locale.US, "%.1f KB", k);
        double m = k / 1024.0;
        if (m < 1024) return String.format(Locale.US, "%.1f MB", m);
        double g = m / 1024.0;
        return String.format(Locale.US, "%.2f GB", g);
    }

    /** 无论数值大小都至少占固定宽度，避免悬浮窗跳动 */
    public static String speedForOverlay(long bytesPerSec) {
        if (bytesPerSec < 0) return "  --  ";
        double v = bytesPerSec;
        if (v < 1024) return String.format(Locale.US, "%5d B/s", (long) v);
        if (v < 1024 * 1024) return String.format(Locale.US, "%5.1fKB/s", v / 1024.0);
        if (v < 1024L * 1024 * 1024) return String.format(Locale.US, "%5.1fMB/s", v / (1024.0 * 1024.0));
        return String.format(Locale.US, "%5.1fGB/s", v / (1024.0 * 1024.0 * 1024.0));
    }
}
