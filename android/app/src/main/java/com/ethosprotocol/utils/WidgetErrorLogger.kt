package com.ethosprotocol.utils

import android.util.Log
import com.google.firebase.perf.FirebasePerformance
import java.util.concurrent.CopyOnWriteArrayList

// ── Error kinds ───────────────────────────────────────────────────────────────

/**
 * Categorises the two failure modes the widget can encounter.
 */
enum class WidgetErrorKind(val key: String) {
    /** The widget failed to load (worker returned without updating the widget due to an API/network failure). */
    LOAD_FAILURE("load_failure"),
    /** A data-refresh call inside the update worker returned an empty/error result. */
    DATA_REFRESH_FAILURE("data_refresh_failure")
}

// ── Data models ───────────────────────────────────────────────────────────────

/** A single captured widget error event kept in the rolling in-memory log. */
data class WidgetErrorEvent(
    val kind: WidgetErrorKind,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

/** Aggregated error metrics returned by [WidgetErrorLogger.summary]. */
data class WidgetErrorSummary(
    /** Total load-failure events in the current rolling window. */
    val loadFailureCount: Int,
    /** Total data-refresh-failure events in the current rolling window. */
    val dataRefreshFailureCount: Int,
    /** Total events of any kind in the current rolling window. */
    val totalErrorCount: Int,
    /** The most recent error event, or `null` if the log is empty. */
    val mostRecentError: WidgetErrorEvent?
)

// ── Singleton logger ──────────────────────────────────────────────────────────

/**
 * Singleton error logger for [com.ethosprotocol.widget.VaultStatusWidget] and
 * [com.ethosprotocol.widget.VaultWidgetUpdateWorker].
 *
 * - Emits [Log.e] entries tagged **WidgetErrorLogger** so failures surface in
 *   Logcat without needing a Firebase Console session.
 * - Records a Firebase Performance custom trace (`widget_error`) for each event
 *   so errors are aggregated in the Firebase Console alongside APM traces.
 * - Maintains a bounded rolling in-memory log (up to [maxEvents] entries, oldest
 *   evicted first) so callers can call [summary] for live error-rate metrics.
 * - Thread-safe: backed by [CopyOnWriteArrayList]; trimming uses a `@Synchronized`
 *   helper to keep the write path race-free.
 *
 * Usage:
 * ```kotlin
 * // In VaultWidgetUpdateWorker.doWork() on API failure:
 * WidgetErrorLogger.logLoadFailure("API returned ${result::class.simpleName}")
 *
 * // When the vault list is empty:
 * WidgetErrorLogger.logDataRefreshFailure("No active vaults returned")
 *
 * // Retrieve aggregated metrics:
 * val summary = WidgetErrorLogger.summary()
 * Log.d(TAG, "Widget load failures: ${summary.loadFailureCount}")
 * ```
 */
object WidgetErrorLogger {

    private const val TAG = "WidgetErrorLogger"
    private const val FIREBASE_TRACE_NAME = "widget_error"

    /** Maximum number of events kept in the rolling window. */
    internal var maxEvents: Int = 50

    private val events = CopyOnWriteArrayList<WidgetErrorEvent>()

    // ── Logging API ───────────────────────────────────────────────────────────

    /**
     * Records a widget load failure.
     *
     * Call this from [com.ethosprotocol.widget.VaultWidgetUpdateWorker.doWork]
     * when the API call fails (network unavailable, HTTP error, etc.).
     *
     * @param message A human-readable description. Must **not** contain PII —
     *   it is written to Logcat and sent to Firebase Performance.
     */
    fun logLoadFailure(message: String) {
        record(WidgetErrorKind.LOAD_FAILURE, message)
    }

    /**
     * Records a widget data-refresh failure.
     *
     * Call this when the worker fetches successfully but returns no usable vault
     * data (e.g., empty active-vault list after filtering).
     *
     * @param message A human-readable description. Must **not** contain PII.
     */
    fun logDataRefreshFailure(message: String) {
        record(WidgetErrorKind.DATA_REFRESH_FAILURE, message)
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    /**
     * Returns aggregated metrics from the current rolling window.
     * Safe to call from any thread.
     */
    fun summary(): WidgetErrorSummary {
        val snapshot = events.toList()
        return WidgetErrorSummary(
            loadFailureCount = snapshot.count { it.kind == WidgetErrorKind.LOAD_FAILURE },
            dataRefreshFailureCount = snapshot.count { it.kind == WidgetErrorKind.DATA_REFRESH_FAILURE },
            totalErrorCount = snapshot.size,
            mostRecentError = snapshot.lastOrNull()
        )
    }

    // ── Reset (for testing) ───────────────────────────────────────────────────

    /**
     * Clears all in-memory events. **Intended for unit tests only.**
     */
    fun reset() {
        events.clear()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun record(kind: WidgetErrorKind, message: String) {
        Log.e(TAG, "Widget error [${kind.key}]: $message")

        // Firebase Performance trace — records error kind and message as
        // attributes so they appear in the Firebase Console traces tab.
        runCatching {
            val trace = FirebasePerformance.getInstance().newTrace(FIREBASE_TRACE_NAME)
            trace.start()
            trace.putAttribute("kind", kind.key)
            // Firebase attribute values are capped at 100 characters.
            trace.putAttribute("message", message.take(100))
            trace.stop()
        }
        // Never let a Firebase failure propagate into widget update logic.

        appendEvent(WidgetErrorEvent(kind = kind, message = message))
    }

    @Synchronized
    private fun appendEvent(event: WidgetErrorEvent) {
        events.add(event)
        // Trim excess entries (drop oldest first).
        while (events.size > maxEvents) {
            events.removeAt(0)
        }
    }
}
