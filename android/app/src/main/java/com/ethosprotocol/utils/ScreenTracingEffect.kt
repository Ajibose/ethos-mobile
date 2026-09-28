package com.ethosprotocol.utils

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.google.firebase.perf.metrics.Trace

/**
 * A side-effect composable that tracks screen load time using Firebase Performance
 * and the [PerformanceMonitor] in-memory log.
 *
 * Place this as the first statement in a screen-level composable:
 *
 * ```kotlin
 * @Composable
 * fun VaultListScreen(...) {
 *     TrackScreen("VaultList")
 *     // ... rest of screen
 * }
 * ```
 *
 * The elapsed time is measured from first composition to disposal (i.e. when the
 * screen leaves the composition tree), giving a practical "time visible" metric.
 * For initial load time semantics, callers that need finer granularity can call
 * [PerformanceMonitor.startScreenTrace] / [PerformanceMonitor.stopScreenTrace]
 * directly.
 */
@Composable
fun TrackScreen(screenName: String) {
    DisposableEffect(screenName) {
        val startTime = SystemClock.elapsedRealtime()
        val trace: Trace = PerformanceMonitor.startScreenTrace(screenName)

        onDispose {
            val durationMs = SystemClock.elapsedRealtime() - startTime
            PerformanceMonitor.stopScreenTrace(trace, screenName, durationMs)
        }
    }
}
