import XCTest
import os
@testable import EthosProtocol

// MARK: - WidgetErrorLoggerTests (#438)
//
// Covers:
//   - logLoadFailure records an event with the correct kind and message
//   - logDataRefreshFailure records an event with the correct kind and message
//   - summary() counts are correct after mixed events
//   - summary().mostRecentError reflects the last recorded event
//   - Rolling window evicts oldest entries when maxEvents is exceeded
//   - reset() clears all state
//   - Thread safety: concurrent writes produce a consistent count

final class WidgetErrorLoggerTests: XCTestCase {

    // Each test gets a fresh logger with a small window so eviction tests are fast.
    private var logger: WidgetErrorLogger!

    override func setUp() {
        super.setUp()
        // Use a small maxEvents and a disabled os_log to keep tests noise-free.
        logger = WidgetErrorLogger(maxEvents: 5, log: .disabled)
    }

    override func tearDown() {
        logger.reset()
        logger = nil
        super.tearDown()
    }

    // -------------------------------------------------------------------------
    // MARK: - logLoadFailure
    // -------------------------------------------------------------------------

    /// #438: logLoadFailure records one event with kind == .loadFailure.
    func test_logLoadFailure_recordsEvent() {
        logger.logLoadFailure(message: "Network timeout")

        let summary = logger.summary()
        XCTAssertEqual(summary.loadFailureCount, 1)
        XCTAssertEqual(summary.dataRefreshFailureCount, 0)
        XCTAssertEqual(summary.totalErrorCount, 1)
    }

    /// #438: logLoadFailure preserves the message text.
    func test_logLoadFailure_preservesMessage() {
        let msg = "API returned 503"
        logger.logLoadFailure(message: msg)

        let event = logger.summary().mostRecentError
        XCTAssertNotNil(event)
        XCTAssertEqual(event?.kind, .loadFailure)
        XCTAssertEqual(event?.message, msg)
    }

    /// #438: logLoadFailure timestamps events at approximately the current time.
    func test_logLoadFailure_stampsTimestamp() {
        let before = Date()
        logger.logLoadFailure(message: "err")
        let after = Date()

        // Allow async queue dispatch to complete.
        let expectation = XCTestExpectation(description: "async append")
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.1) { expectation.fulfill() }
        wait(for: [expectation], timeout: 1)

        let event = logger.summary().mostRecentError
        XCTAssertNotNil(event)
        if let ts = event?.timestamp {
            XCTAssertGreaterThanOrEqual(ts, before)
            XCTAssertLessThanOrEqual(ts, after.addingTimeInterval(1))
        }
    }

    // -------------------------------------------------------------------------
    // MARK: - logDataRefreshFailure
    // -------------------------------------------------------------------------

    /// #438: logDataRefreshFailure records one event with kind == .dataRefreshFailure.
    func test_logDataRefreshFailure_recordsEvent() {
        logger.logDataRefreshFailure(message: "Empty vault list")

        let summary = logger.summary()
        XCTAssertEqual(summary.dataRefreshFailureCount, 1)
        XCTAssertEqual(summary.loadFailureCount, 0)
        XCTAssertEqual(summary.totalErrorCount, 1)
    }

    /// #438: logDataRefreshFailure preserves the message text.
    func test_logDataRefreshFailure_preservesMessage() {
        let msg = "Data refresh returned no active vaults (total vaults: 3)"
        logger.logDataRefreshFailure(message: msg)

        let event = logger.summary().mostRecentError
        XCTAssertEqual(event?.kind, .dataRefreshFailure)
        XCTAssertEqual(event?.message, msg)
    }

    // -------------------------------------------------------------------------
    // MARK: - summary()
    // -------------------------------------------------------------------------

    /// #438: summary() counts load and refresh failures independently.
    func test_summary_countsKindsSeparately() {
        logger.logLoadFailure(message: "load 1")
        logger.logLoadFailure(message: "load 2")
        logger.logDataRefreshFailure(message: "refresh 1")

        // Let the async queue flush.
        flushLogger()

        let summary = logger.summary()
        XCTAssertEqual(summary.loadFailureCount, 2)
        XCTAssertEqual(summary.dataRefreshFailureCount, 1)
        XCTAssertEqual(summary.totalErrorCount, 3)
    }

    /// #438: mostRecentError is nil on a fresh logger.
    func test_summary_mostRecentError_nilWhenEmpty() {
        XCTAssertNil(logger.summary().mostRecentError)
    }

    /// #438: mostRecentError reflects the last recorded event regardless of kind.
    func test_summary_mostRecentError_isLastEvent() {
        logger.logLoadFailure(message: "first")
        logger.logDataRefreshFailure(message: "second")

        flushLogger()

        let event = logger.summary().mostRecentError
        XCTAssertEqual(event?.kind, .dataRefreshFailure)
        XCTAssertEqual(event?.message, "second")
    }

    // -------------------------------------------------------------------------
    // MARK: - Rolling window eviction
    // -------------------------------------------------------------------------

    /// #438: When more than maxEvents events are logged, the oldest are evicted.
    func test_rollingWindow_evictsOldestEntries() {
        // logger.maxEvents == 5; log 6 events.
        for i in 1...6 {
            logger.logLoadFailure(message: "failure \(i)")
        }

        flushLogger()

        let summary = logger.summary()
        XCTAssertEqual(summary.totalErrorCount, 5, "Window is capped at maxEvents=5")
        // The oldest event (failure 1) must have been evicted.
        // Most recent must be "failure 6".
        XCTAssertEqual(summary.mostRecentError?.message, "failure 6")
    }

    /// #438: Eviction preserves the correct total count after many events.
    func test_rollingWindow_totalCountNeverExceedsMax() {
        for i in 1...20 {
            if i.isMultiple(of: 2) {
                logger.logLoadFailure(message: "load \(i)")
            } else {
                logger.logDataRefreshFailure(message: "refresh \(i)")
            }
        }

        flushLogger()

        let summary = logger.summary()
        XCTAssertLessThanOrEqual(summary.totalErrorCount, 5)
    }

    // -------------------------------------------------------------------------
    // MARK: - reset()
    // -------------------------------------------------------------------------

    /// #438: reset() clears all events and returns zero counts.
    func test_reset_clearsAllState() {
        logger.logLoadFailure(message: "a")
        logger.logDataRefreshFailure(message: "b")

        flushLogger()

        logger.reset()

        let summary = logger.summary()
        XCTAssertEqual(summary.totalErrorCount, 0)
        XCTAssertEqual(summary.loadFailureCount, 0)
        XCTAssertEqual(summary.dataRefreshFailureCount, 0)
        XCTAssertNil(summary.mostRecentError)
    }

    /// #438: After reset(), new events are recorded correctly.
    func test_reset_allowsFreshRecording() {
        logger.logLoadFailure(message: "old")
        flushLogger()
        logger.reset()

        logger.logDataRefreshFailure(message: "fresh")
        flushLogger()

        let summary = logger.summary()
        XCTAssertEqual(summary.totalErrorCount, 1)
        XCTAssertEqual(summary.dataRefreshFailureCount, 1)
        XCTAssertEqual(summary.mostRecentError?.message, "fresh")
    }

    // -------------------------------------------------------------------------
    // MARK: - Thread safety
    // -------------------------------------------------------------------------

    /// #438: Concurrent writes from multiple threads must not crash and must
    /// produce a total count that does not exceed maxEvents.
    func test_concurrentWrites_doNotCrashOrExceedCap() {
        let group = DispatchGroup()
        let iterations = 50

        for i in 0..<iterations {
            group.enter()
            DispatchQueue.global().async {
                self.logger.logLoadFailure(message: "concurrent \(i)")
                group.leave()
            }
        }

        group.wait()
        flushLogger()

        let summary = logger.summary()
        XCTAssertLessThanOrEqual(summary.totalErrorCount, 5,
                                  "Cap must hold under concurrent writes")
    }

    // -------------------------------------------------------------------------
    // MARK: - WidgetErrorKind raw values
    // -------------------------------------------------------------------------

    /// #438: WidgetErrorKind raw values match the strings used in os_log output.
    func test_widgetErrorKind_rawValues() {
        XCTAssertEqual(WidgetErrorKind.loadFailure.rawValue, "load_failure")
        XCTAssertEqual(WidgetErrorKind.dataRefreshFailure.rawValue, "data_refresh_failure")
    }

    // -------------------------------------------------------------------------
    // MARK: - Helpers
    // -------------------------------------------------------------------------

    /// Spins briefly to let the logger's private async queue process appends.
    private func flushLogger() {
        let exp = expectation(description: "flush")
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 0.1) { exp.fulfill() }
        wait(for: [exp], timeout: 2)
    }
}
