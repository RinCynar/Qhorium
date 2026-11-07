package rj.qmce.lite.util

import android.os.SystemClock
import android.util.Log
import java.util.Locale

object PerfTrace {
    private const val TAG = "QMCE-PerfTrace"
    private var baseTimeNs = SystemClock.elapsedRealtimeNanos()
    private var lastMarkNs = baseTimeNs

    fun reset(tag: String = "AppStartup") {
        baseTimeNs = SystemClock.elapsedRealtimeNanos()
        lastMarkNs = baseTimeNs
        Log.i(TAG, "==== [$tag] Timeline Started ====")
    }

    fun mark(stage: String, details: String = "") {
        val now = SystemClock.elapsedRealtimeNanos()
        val deltaMs = (now - lastMarkNs) / 1_000_000.0
        val totalMs = (now - baseTimeNs) / 1_000_000.0
        lastMarkNs = now
        val detailStr = if (details.isNotBlank()) " | $details" else ""
        Log.i(
            TAG,
            String.format(
                Locale.US,
                "+%.2fms (total %.2fms) [%s]%s",
                deltaMs,
                totalMs,
                stage,
                detailStr,
            ),
        )
    }
}
