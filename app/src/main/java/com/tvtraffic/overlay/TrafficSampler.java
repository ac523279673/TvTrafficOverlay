package com.tvtraffic.overlay;

import android.net.TrafficStats;

/**
 * 通过 TrafficStats 读取设备累计流量，两次采样差分得到实时速率。
 * 覆盖所有网卡（含 Wi-Fi / 有线 / 移动网络），是系统级统计，无需额外权限。
 */
public final class TrafficSampler {

    private long lastRx = -1L;
    private long lastTx = -1L;
    private long lastTs = 0L;

    /** 重置基准，切换网络或服务重启后调用，避免出现一次巨大的瞬时值 */
    public void reset() {
        lastRx = TrafficStats.getTotalRxBytes();
        lastTx = TrafficStats.getTotalTxBytes();
        lastTs = System.currentTimeMillis();
    }

    /**
     * @return long[]{rxBytesPerSec, txBytesPerSec}，取不到时返回 {-1,-1}
     */
    public long[] sample() {
        long now = System.currentTimeMillis();
        long rx = TrafficStats.getTotalRxBytes();
        long tx = TrafficStats.getTotalTxBytes();

        if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED) {
            return new long[]{-1L, -1L};
        }

        if (lastRx < 0 || lastTs == 0) {
            lastRx = rx;
            lastTx = tx;
            lastTs = now;
            return new long[]{0L, 0L};
        }

        long dt = now - lastTs;
        if (dt <= 0) return new long[]{0L, 0L};

        long drx = rx - lastRx;
        long dtx = tx - lastTx;
        // 计数回绕或网络重置，丢弃本次
        if (drx < 0 || dtx < 0) {
            lastRx = rx;
            lastTx = tx;
            lastTs = now;
            return new long[]{0L, 0L};
        }

        lastRx = rx;
        lastTx = tx;
        lastTs = now;

        long rxRate = drx * 1000L / dt;
        long txRate = dtx * 1000L / dt;
        return new long[]{rxRate, txRate};
    }
}
