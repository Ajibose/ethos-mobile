import Foundation
import os

// MARK: - Widget Error Kind

/// Categorises the two failure modes the widget can encounter.
public enum WidgetErrorKind: String, Sendable {
    /// The widget failed to load (timeline provider returned an error entry
    /// from `timeline(for:in:)` due to an API/network failure).
    case loadFailure = "load_failure"
    /// A data-refresh call inside the timeline provider threw or returned an
    /// empty/error result.
    case dataRefreshFailure = "data_refresh_failure"
}

// MARK: - Widget Error Event

/// A single captured widget error event, kept in the rolling in-memory log.
public struct WidgetErrorEvent: Sendable {
    public let kind: WidgetErrorKind
    public let message: String
    public let timestamp: Date
}

// MARK: - Widget Error Summary

/// Aggregated error metrics returned by `WidgetErrorLogger.summary()`.
public struct WidgetErrorSummary: Sendable {
    /// Total load-failure events recorded in the current rolling window.
    public let loadFailureCount: Int
    /// Total data-refresh-failure events recorded in the current rolling window.
    public let dataRefreshFailureCount: Int
    /// Total events of any kind in the current rolling window.
    public let totalErrorCount: Int
    /// The most recent error event, or `nil` if the log is empty.
    public let mostRecentError: WidgetErrorEvent?
}

// MARK: - WidgetErrorLogger

/// Singleton logger for TTLWidget errors.
///
/// - Emits structured `os_log` entries (`.error` level) so failures surface in
///   the system Console without needing Instruments.
/// - Maintains a bounded rolling in-memory log (up to `maxEvents` entries, oldest
///   evicted first) so callers can call `summary()` for live error-rate metrics.
/// - Thread-safe: all state mutations run on a private serial queue.
///
/// Usage:
/// ```swift
/// // In TTLTimelineProvider.timeline(for:in:):
/// WidgetErrorLogger.shared.logLoadFailure(message: error.localizedDescription)
///
/// // In a data-refresh path:
/// WidgetErrorLogger.shared.logDataRefreshFailure(message: "Empty vault list")
///
/// // Retrieve metrics:
/// let summary = WidgetErrorLogger.shared.summary()
/// print("Widget load failures: \(summary.loadFailureCount)")
/// ```
public final class WidgetErrorLogger: @unchecked Sendable {

    // MARK: Singleton

    public static let shared = WidgetErrorLogger()

    // MARK: Constants

    /// Maximum number of events kept in the rolling window.
    private let maxEvents: Int

    // MARK: os_log

    private let log: OSLog

    // MARK: Private state (serial-queue-protected)

    private let queue = DispatchQueue(label: "com.ethosprotocol.WidgetErrorLogger", qos: .utility)
    private var events: [WidgetErrorEvent] = []

    // MARK: Init

    /// Primary initialiser.
    /// - Parameter maxEvents: Rolling-window capacity. Defaults to 50.
    /// - Parameter log: `OSLog` instance — override in tests to capture log output.
    init(maxEvents: Int = 50, log: OSLog = OSLog(subsystem: "com.ethosprotocol", category: "WidgetErrors")) {
        self.maxEvents = maxEvents
        self.log = log
    }

    // MARK: - Logging API

    /// Records a widget load failure.
    ///
    /// Call this from `TTLTimelineProvider.timeline(for:in:)` when the API call
    /// throws or returns no usable vaults.
    ///
    /// - Parameter message: A human-readable description of the failure.
    ///   Must **not** contain PII — it is written to the system log.
    public func logLoadFailure(message: String) {
        log(kind: .loadFailure, message: message)
    }

    /// Records a widget data-refresh failure.
    ///
    /// Call this when a background or foreground data refresh inside the widget
    /// extension fails (e.g., the API returns an unexpected empty list).
    ///
    /// - Parameter message: A human-readable description of the failure.
    ///   Must **not** contain PII — it is written to the system log.
    public func logDataRefreshFailure(message: String) {
        log(kind: .dataRefreshFailure, message: message)
    }

    // MARK: - Summary

    /// Returns aggregated metrics from the current rolling window.
    /// Safe to call from any thread.
    public func summary() -> WidgetErrorSummary {
        queue.sync {
            let loadCount = events.filter { $0.kind == .loadFailure }.count
            let refreshCount = events.filter { $0.kind == .dataRefreshFailure }.count
            return WidgetErrorSummary(
                loadFailureCount: loadCount,
                dataRefreshFailureCount: refreshCount,
                totalErrorCount: events.count,
                mostRecentError: events.last
            )
        }
    }

    // MARK: - Reset (for testing)

    /// Clears all in-memory events. **Intended for unit tests only.**
    public func reset() {
        queue.sync {
            events.removeAll()
        }
    }

    // MARK: - Private helpers

    private func log(kind: WidgetErrorKind, message: String) {
        let event = WidgetErrorEvent(kind: kind, message: message, timestamp: Date())

        os_log(
            "Widget error [%{public}s]: %{public}s",
            log: log,
            type: .error,
            kind.rawValue,
            message
        )

        queue.async { [weak self] in
            guard let self else { return }
            self.events.append(event)
            if self.events.count > self.maxEvents {
                self.events.removeFirst(self.events.count - self.maxEvents)
            }
        }
    }
}
