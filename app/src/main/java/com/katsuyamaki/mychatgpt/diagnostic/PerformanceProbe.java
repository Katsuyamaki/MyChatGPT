package com.katsuyamaki.mychatgpt.diagnostic;

import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import com.katsuyamaki.mychatgpt.BuildConfig;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Diagnostic-only monotonic performance markers for owner-device benchmarking.
 *
 * This class owns timing telemetry only. It must not change runtime behavior,
 * read conversation content, or become a production state machine.
 */
public final class PerformanceProbe {

    private static final String TAG = "MyChatGPTPerf";
    private static final long PROBE_START_MS = SystemClock.elapsedRealtime();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private PerformanceProbe() {}

    public static void mark(String event) {
        mark(event, null);
    }

    public static void mark(String event, String detail) {
        if (!BuildConfig.EXPERIMENTAL) return;

        long now = SystemClock.elapsedRealtime();
        StringBuilder line = new StringBuilder(160)
                .append("perf seq=").append(SEQUENCE.incrementAndGet())
                .append(" event=").append(event)
                .append(" uptimeMs=").append(now)
                .append(" sinceProbeMs=").append(now - PROBE_START_MS)
                .append(" pid=").append(Process.myPid())
                .append(" thread=").append(Thread.currentThread().getName());

        if (detail != null && !detail.isEmpty()) {
            line.append(" ").append(detail);
        }

        Log.d(TAG, line.toString());
    }
}
