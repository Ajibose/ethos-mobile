import SwiftUI
import Foundation

// MARK: - ScreenTracingModifier

/// A `ViewModifier` that measures how long a screen takes from `.onAppear` to
/// `.onDisappear` and records the result in `PerformanceMonitor`.
///
/// Attach via the convenience extension:
/// ```swift
/// VaultListView()
///     .trackScreen("VaultList")
/// ```
struct ScreenTracingModifier: ViewModifier {
    let screenName: String

    /// Wall-clock start time captured in `.onAppear`, set via `CACurrentMediaTime()`
    /// which is monotonic and unaffected by system clock changes.
    @State private var appearTime: CFTimeInterval = 0
    /// `OSSignpostID` returned by `PerformanceMonitor.beginScreenTrace` so the
    /// interval can be closed in `.onDisappear` even if the view is re-entered.
    @State private var signpostID: OSSignpostID? = nil

    func body(content: Content) -> some View {
        content
            .onAppear {
                appearTime = CACurrentMediaTime()
                signpostID = PerformanceMonitor.shared.beginScreenTrace(name: screenName)
            }
            .onDisappear {
                let durationMs = (CACurrentMediaTime() - appearTime) * 1_000
                if let id = signpostID {
                    PerformanceMonitor.shared.endScreenTrace(name: screenName, id: id)
                }
                PerformanceMonitor.shared.recordScreenLoad(screenName: screenName, durationMs: durationMs)
                // Reset so a re-appear starts a fresh interval.
                signpostID = nil
                appearTime = 0
            }
    }
}

// MARK: - View extension

extension View {
    /// Attaches screen-load tracing to any SwiftUI view.
    ///
    /// Records an `os_signpost` interval (visible in Instruments) and a rolling
    /// in-memory `ScreenMetric` entry in `PerformanceMonitor.shared`.
    ///
    /// - Parameter name: A short, stable identifier for the screen (e.g. `"VaultList"`).
    func trackScreen(_ name: String) -> some View {
        modifier(ScreenTracingModifier(screenName: name))
    }
}
