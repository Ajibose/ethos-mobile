import Foundation
import os
import os.signpost

// MARK: - Thresholds

struct PerformanceThresholds {
    let apiSlowThresholdMs: Double
    let screenSlowThresholdMs: Double
    let maxAPIMetrics: Int
    let maxScreenMetrics: Int

    static let defaults = PerformanceThresholds(
        apiSlowThresholdMs: 2000.0,
        screenSlowThresholdMs: 500.0,
        maxAPIMetrics: 100,
        maxScreenMetrics: 50
    )
}

// MARK: - Metric structs

struct APIMetric {
    let path: String
    let method: String
    let durationMs: Double
    let statusCode: Int
    let timestamp: Date
}

struct ScreenMetric {
    let screenName: String
    let loadTimeMs: Double
    let timestamp: Date
}

// MARK: - Summary

struct PerformanceSummary {
    /// Median API response time across the in-memory window.
    let apiP50Ms: Double
    /// 95th-percentile API response time.
    let apiP95Ms: Double
    /// 99th-percentile API response time.
    let apiP99Ms: Double
    /// Number of API calls that exceeded `PerformanceThresholds.apiSlowThresholdMs`.
    let slowAPICallCount: Int
    /// Number of screen loads that exceeded `PerformanceThresholds.screenSlowThresholdMs`.
    let slowScreenCount: Int
    /// Total API calls recorded in the current window (capped at maxAPIMetrics).
    let totalAPICalls: Int
    /// Total screen loads recorded in the current window (capped at maxScreenMetrics).
    let totalScreenLoads: Int
    /// Average screen load time, or nil when no screens have been recorded.
    let averageScreenLoadMs: Double?
}

// MARK: - PerformanceMonitor

/// Zero-overhead APM singleton backed by `os_signpost` for Instruments tracing
/// and a serial-queue-protected rolling in-memory log for in-process analytics.
///
/// All public methods are thread-safe. The log is capped at `PerformanceThresholds`
/// limits (100 API metrics / 50 screen metrics) — oldest entries are evicted first.
///
/// Slow calls are emitted at `.error` level to `os_log` so they surface in the
/// Console app without requiring an Instruments session.
final class PerformanceMonitor {

    // MARK: Singleton

    static let shared = PerformanceMonitor()

    // MARK: Private state

    private let queue = DispatchQueue(label: "com.ethosprotocol.PerformanceMonitor", qos: .utility)
    private let thresholds: PerformanceThresholds

    // os_signpost machinery — mirrors the subsystem/category convention used in
    // StartupTracing.swift so all APM intervals live under the same subsystem.
    private let log: OSLog
    private let apiLog: OSLog

    private var apiMetrics: [APIMetric] = []
    private var screenMetrics: [ScreenMetric] = []

    // MARK: Init

    private init(thresholds: PerformanceThresholds = .defaults) {
        self.thresholds = thresholds
        self.log = OSLog(subsystem: "com.ethosprotocol", category: "ScreenPerformance")
        self.apiLog = OSLog(subsystem: "com.ethosprotocol", category: "APIPerformance")
    }

    // MARK: - Screen tracing (os_signpost interval)

    /// Begins an `os_signpost` interval for the named screen.
    /// - Returns: The `OSSignpostID` needed to close the interval with `endScreenTrace`.
    func beginScreenTrace(name: String) -> OSSignpostID {
        let id = OSSignpostID(log: log)
        os_signpost(.begin, log: log, name: "Screen Load", signpostID: id, "%{public}s", name)
        return id
    }

    /// Ends the `os_signpost` interval opened by `beginScreenTrace`.
    func endScreenTrace(name: String, id: OSSignpostID) {
        os_signpost(.end, log: log, name: "Screen Load", signpostID: id, "%{public}s", name)
    }

    /// Records a measured screen load duration in the rolling in-memory log.
    /// Call after `endScreenTrace` with the wall-clock elapsed time.
    func recordScreenLoad(screenName: String, durationMs: Double) {
        let metric = ScreenMetric(screenName: screenName, loadTimeMs: durationMs, timestamp: Date())
        queue.async { [weak self] in
            guard let self else { return }
            self.screenMetrics.append(metric)
            // Evict oldest entries to respect the rolling-window cap.
            if self.screenMetrics.count > self.thresholds.maxScreenMetrics {
                self.screenMetrics.removeFirst(self.screenMetrics.count - self.thresholds.maxScreenMetrics)
            }
            if durationMs > self.thresholds.screenSlowThresholdMs {
                os_log(
                    "Slow screen load: %{public}s took %.0fms (threshold %.0fms)",
                    log: self.log, type: .error,
                    screenName, durationMs, self.thresholds.screenSlowThresholdMs
                )
            }
        }
    }

    // MARK: - API call recording

    /// Records an API call result in the rolling in-memory log and emits an
    /// `os_signpost` event. Slow calls are logged at `.error` level.
    func recordAPICall(path: String, method: String, durationMs: Double, statusCode: Int) {
        let metric = APIMetric(
            path: path,
            method: method,
            durationMs: durationMs,
            statusCode: statusCode,
            timestamp: Date()
        )
        // os_signpost event visible in Instruments' Signpost instrument.
        os_signpost(
            .event,
            log: apiLog,
            name: "API Call",
            "%{public}s %{public}s %dms status=%d",
            method, path, Int(durationMs), statusCode
        )
        queue.async { [weak self] in
            guard let self else { return }
            self.apiMetrics.append(metric)
            if self.apiMetrics.count > self.thresholds.maxAPIMetrics {
                self.apiMetrics.removeFirst(self.apiMetrics.count - self.thresholds.maxAPIMetrics)
            }
            if durationMs > self.thresholds.apiSlowThresholdMs {
                os_log(
                    "Slow API call: %{public}s %{public}s took %.0fms (threshold %.0fms) status=%d",
                    log: self.apiLog, type: .error,
                    method, path, durationMs, self.thresholds.apiSlowThresholdMs, statusCode
                )
            }
        }
    }

    // MARK: - Summary

    /// Returns p50/p95/p99 percentiles for the in-memory API call window, plus
    /// slow-call/screen counts. Safe to call from any thread.
    func summary() -> PerformanceSummary {
        queue.sync {
            let durations = apiMetrics.map(\.durationMs).sorted()
            let slowAPI = apiMetrics.filter { $0.durationMs > thresholds.apiSlowThresholdMs }.count
            let slowScreen = screenMetrics.filter { $0.loadTimeMs > thresholds.screenSlowThresholdMs }.count
            let avgScreen: Double? = screenMetrics.isEmpty
                ? nil
                : screenMetrics.reduce(0.0) { $0 + $1.loadTimeMs } / Double(screenMetrics.count)

            return PerformanceSummary(
                apiP50Ms: percentile(durations, 0.50),
                apiP95Ms: percentile(durations, 0.95),
                apiP99Ms: percentile(durations, 0.99),
                slowAPICallCount: slowAPI,
                slowScreenCount: slowScreen,
                totalAPICalls: apiMetrics.count,
                totalScreenLoads: screenMetrics.count,
                averageScreenLoadMs: avgScreen
            )
        }
    }

    // MARK: - Reset (for testing)

    /// Clears all in-memory metrics. Intended for unit tests only.
    func reset() {
        queue.sync {
            apiMetrics.removeAll()
            screenMetrics.removeAll()
        }
    }

    // MARK: - Private helpers

    /// Nearest-rank percentile from a pre-sorted array. Returns 0 for an empty array.
    private func percentile(_ sorted: [Double], _ p: Double) -> Double {
        guard !sorted.isEmpty else { return 0 }
        // Nearest-rank method: index = ceil(p * n) - 1, clamped to valid range.
        let index = max(0, min(sorted.count - 1, Int(ceil(p * Double(sorted.count))) - 1))
        return sorted[index]
    }
}
