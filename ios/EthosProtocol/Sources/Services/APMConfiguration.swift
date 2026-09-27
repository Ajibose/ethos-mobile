// APMConfiguration.swift
// Centralized performance alert thresholds for the Ethos Protocol iOS app.
//
// Thresholds are based on mobile performance research (Google RAIL model):
//   response < 100 ms  → feels instant
//   response < 1 000 ms → noticeable but acceptable
//   response > 5 000 ms → user abandons the interaction
//
// These values are consumed by PerformanceMonitor at runtime and can be
// referenced directly in unit tests or conditional UI warnings.

import Foundation

/// Namespace enum — not instantiable — that holds all APM threshold constants
/// and retention settings for the Ethos Protocol iOS app.
enum APMConfiguration {

    // MARK: - API Call Thresholds

    /// Flag a slow API call when its round-trip exceeds this duration (ms).
    static let apiSlowThresholdMs: Double = 2_000

    /// Flag a critical API call when its round-trip exceeds this duration (ms).
    static let apiCriticalThresholdMs: Double = 5_000

    // MARK: - Screen Load Thresholds

    /// Flag a slow screen load when the appear→disappear interval exceeds this (ms).
    static let screenSlowThresholdMs: Double = 500

    /// Flag a critical screen load when the appear→disappear interval exceeds this (ms).
    static let screenCriticalThresholdMs: Double = 2_000

    // MARK: - App Startup Threshold

    /// Flag slow app startup when cold-launch exceeds this duration (ms).
    static let startupSlowThresholdMs: Int = 3_000

    // MARK: - In-Memory Retention

    /// Maximum number of API metrics kept in the rolling in-memory buffer.
    static let apiMetricsRetentionCount: Int = 100

    /// Maximum number of screen metrics kept in the rolling in-memory buffer.
    static let screenMetricsRetentionCount: Int = 50

    // MARK: - Alerting

    /// Master switch — when `false`, no threshold-breach logs are emitted.
    /// Defaults to `true`; override in tests via `PerformanceMonitor.reset()`.
    static let alertingEnabled: Bool = true
}
