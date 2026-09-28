package com.ethosprotocol.utils

/**
 * Centralized performance alert thresholds for the Ethos Protocol Android app.
 *
 * Thresholds are based on mobile performance research (Google RAIL model):
 *   response < 100 ms  → feels instant
 *   response < 1 000 ms → noticeable but acceptable
 *   response > 5 000 ms → user abandons the interaction
 *
 * These constants are consumed by [PerformanceMonitor] at runtime and can be
 * referenced directly in unit tests or conditional UI warnings.
 */
object APMConfiguration {

    // -------------------------------------------------------------------------
    // API Call Thresholds
    // -------------------------------------------------------------------------

    /** Flag a slow API call when its round-trip exceeds this duration (ms). */
    const val API_SLOW_THRESHOLD_MS: Long = 2000L

    /** Flag a critical API call when its round-trip exceeds this duration (ms). */
    const val API_CRITICAL_THRESHOLD_MS: Long = 5000L

    // -------------------------------------------------------------------------
    // Screen Load Thresholds
    // -------------------------------------------------------------------------

    /** Flag a slow screen load when the composable's active lifetime exceeds this (ms). */
    const val SCREEN_SLOW_THRESHOLD_MS: Long = 500L

    /** Flag a critical screen load when the composable's active lifetime exceeds this (ms). */
    const val SCREEN_CRITICAL_THRESHOLD_MS: Long = 2000L

    // -------------------------------------------------------------------------
    // App Startup Threshold
    // -------------------------------------------------------------------------

    /** Flag slow app startup when cold-launch exceeds this duration (ms). */
    const val STARTUP_SLOW_THRESHOLD_MS: Long = 3000L

    // -------------------------------------------------------------------------
    // In-Memory Retention
    // -------------------------------------------------------------------------

    /** Maximum number of API metrics kept in the rolling in-memory buffer. */
    const val API_METRICS_RETENTION_COUNT: Int = 100

    /** Maximum number of screen metrics kept in the rolling in-memory buffer. */
    const val SCREEN_METRICS_RETENTION_COUNT: Int = 50

    // -------------------------------------------------------------------------
    // Alerting
    // -------------------------------------------------------------------------

    /**
     * Master switch — when `false`, no threshold-breach log entries are emitted.
     * Defaults to `true`; override in tests via [PerformanceMonitor.reset].
     */
    const val ALERTING_ENABLED: Boolean = true
}
