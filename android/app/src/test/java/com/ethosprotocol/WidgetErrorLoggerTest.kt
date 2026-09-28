package com.ethosprotocol

import com.ethosprotocol.utils.WidgetErrorKind
import com.ethosprotocol.utils.WidgetErrorLogger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [WidgetErrorLogger] (#438).
 *
 * Covers:
 * - logLoadFailure records an event with kind LOAD_FAILURE
 * - logDataRefreshFailure records an event with kind DATA_REFRESH_FAILURE
 * - summary() counts each kind independently
 * - summary().mostRecentError reflects the last recorded event
 * - Rolling window evicts oldest entries when maxEvents is exceeded
 * - reset() clears all state
 * - Thread safety: concurrent writes produce a capped, consistent count
 * - WidgetErrorKind.key strings match expected log tag values
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class WidgetErrorLoggerTest {

    @Before
    fun setUp() {
        // Each test starts with a clean slate and a small window so eviction tests are fast.
        WidgetErrorLogger.reset()
        WidgetErrorLogger.maxEvents = 5
    }

    @After
    fun tearDown() {
        WidgetErrorLogger.reset()
        WidgetErrorLogger.maxEvents = 50 // restore default
    }

    // -------------------------------------------------------------------------
    // logLoadFailure
    // -------------------------------------------------------------------------

    /** #438: logLoadFailure records one LOAD_FAILURE event. */
    @Test
    fun `logLoadFailure records a load failure event`() {
        WidgetErrorLogger.logLoadFailure("Network timeout")

        val summary = WidgetErrorLogger.summary()
        assertEquals(1, summary.loadFailureCount)
        assertEquals(0, summary.dataRefreshFailureCount)
        assertEquals(1, summary.totalErrorCount)
    }

    /** #438: logLoadFailure preserves the message text. */
    @Test
    fun `logLoadFailure preserves message text`() {
        val msg = "API returned 503"
        WidgetErrorLogger.logLoadFailure(msg)

        val event = WidgetErrorLogger.summary().mostRecentError
        assertNotNull(event)
        assertEquals(WidgetErrorKind.LOAD_FAILURE, event?.kind)
        assertEquals(msg, event?.message)
    }

    /** #438: logLoadFailure timestamps the event at approximately the current time. */
    @Test
    fun `logLoadFailure sets a recent timestamp`() {
        val before = System.currentTimeMillis()
        WidgetErrorLogger.logLoadFailure("err")
        val after = System.currentTimeMillis()

        val ts = WidgetErrorLogger.summary().mostRecentError?.timestamp ?: 0L
        assertTrue("timestamp should be >= before", ts >= before)
        assertTrue("timestamp should be <= after", ts <= after + 50)
    }

    // -------------------------------------------------------------------------
    // logDataRefreshFailure
    // -------------------------------------------------------------------------

    /** #438: logDataRefreshFailure records one DATA_REFRESH_FAILURE event. */
    @Test
    fun `logDataRefreshFailure records a data refresh failure event`() {
        WidgetErrorLogger.logDataRefreshFailure("Empty vault list")

        val summary = WidgetErrorLogger.summary()
        assertEquals(1, summary.dataRefreshFailureCount)
        assertEquals(0, summary.loadFailureCount)
        assertEquals(1, summary.totalErrorCount)
    }

    /** #438: logDataRefreshFailure preserves the message text. */
    @Test
    fun `logDataRefreshFailure preserves message text`() {
        val msg = "Data refresh returned no active vaults (total: 3)"
        WidgetErrorLogger.logDataRefreshFailure(msg)

        val event = WidgetErrorLogger.summary().mostRecentError
        assertEquals(WidgetErrorKind.DATA_REFRESH_FAILURE, event?.kind)
        assertEquals(msg, event?.message)
    }

    // -------------------------------------------------------------------------
    // summary()
    // -------------------------------------------------------------------------

    /** #438: summary() counts load and refresh failures independently. */
    @Test
    fun `summary counts kinds independently`() {
        WidgetErrorLogger.logLoadFailure("load 1")
        WidgetErrorLogger.logLoadFailure("load 2")
        WidgetErrorLogger.logDataRefreshFailure("refresh 1")

        val summary = WidgetErrorLogger.summary()
        assertEquals(2, summary.loadFailureCount)
        assertEquals(1, summary.dataRefreshFailureCount)
        assertEquals(3, summary.totalErrorCount)
    }

    /** #438: mostRecentError is null when no events have been recorded. */
    @Test
    fun `summary mostRecentError is null when empty`() {
        assertNull(WidgetErrorLogger.summary().mostRecentError)
    }

    /** #438: mostRecentError reflects the last recorded event regardless of kind. */
    @Test
    fun `summary mostRecentError is last event`() {
        WidgetErrorLogger.logLoadFailure("first")
        WidgetErrorLogger.logDataRefreshFailure("second")

        val event = WidgetErrorLogger.summary().mostRecentError
        assertEquals(WidgetErrorKind.DATA_REFRESH_FAILURE, event?.kind)
        assertEquals("second", event?.message)
    }

    // -------------------------------------------------------------------------
    // Rolling window eviction
    // -------------------------------------------------------------------------

    /** #438: When more than maxEvents events are logged, the oldest are evicted. */
    @Test
    fun `rolling window evicts oldest entries`() {
        // maxEvents == 5; log 6 events.
        for (i in 1..6) {
            WidgetErrorLogger.logLoadFailure("failure $i")
        }

        val summary = WidgetErrorLogger.summary()
        assertEquals("Window is capped at maxEvents=5", 5, summary.totalErrorCount)
        assertEquals("Most recent event is the last one logged", "failure 6",
            summary.mostRecentError?.message)
    }

    /** #438: Total count never exceeds maxEvents regardless of how many are logged. */
    @Test
    fun `rolling window total count never exceeds max`() {
        for (i in 1..20) {
            if (i % 2 == 0) {
                WidgetErrorLogger.logLoadFailure("load $i")
            } else {
                WidgetErrorLogger.logDataRefreshFailure("refresh $i")
            }
        }

        val total = WidgetErrorLogger.summary().totalErrorCount
        assertTrue("Total $total must be <= 5", total <= 5)
    }

    // -------------------------------------------------------------------------
    // reset()
    // -------------------------------------------------------------------------

    /** #438: reset() clears all events and returns zero counts. */
    @Test
    fun `reset clears all state`() {
        WidgetErrorLogger.logLoadFailure("a")
        WidgetErrorLogger.logDataRefreshFailure("b")
        WidgetErrorLogger.reset()

        val summary = WidgetErrorLogger.summary()
        assertEquals(0, summary.totalErrorCount)
        assertEquals(0, summary.loadFailureCount)
        assertEquals(0, summary.dataRefreshFailureCount)
        assertNull(summary.mostRecentError)
    }

    /** #438: After reset(), new events are recorded correctly. */
    @Test
    fun `reset allows fresh recording`() {
        WidgetErrorLogger.logLoadFailure("old")
        WidgetErrorLogger.reset()

        WidgetErrorLogger.logDataRefreshFailure("fresh")

        val summary = WidgetErrorLogger.summary()
        assertEquals(1, summary.totalErrorCount)
        assertEquals(1, summary.dataRefreshFailureCount)
        assertEquals("fresh", summary.mostRecentError?.message)
    }

    // -------------------------------------------------------------------------
    // Thread safety
    // -------------------------------------------------------------------------

    /**
     * #438: Concurrent writes from multiple threads must not crash and must
     * produce a total count that does not exceed maxEvents.
     */
    @Test
    fun `concurrent writes do not crash or exceed cap`() {
        val executor = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(50)

        repeat(50) { i ->
            executor.submit {
                WidgetErrorLogger.logLoadFailure("concurrent $i")
                latch.countDown()
            }
        }

        assertTrue("Timed out waiting for concurrent writes", latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        val total = WidgetErrorLogger.summary().totalErrorCount
        assertTrue("Total $total must not exceed maxEvents=5", total <= 5)
    }

    // -------------------------------------------------------------------------
    // WidgetErrorKind key strings
    // -------------------------------------------------------------------------

    /** #438: WidgetErrorKind.key values match the strings expected in log output. */
    @Test
    fun `WidgetErrorKind key values are correct`() {
        assertEquals("load_failure", WidgetErrorKind.LOAD_FAILURE.key)
        assertEquals("data_refresh_failure", WidgetErrorKind.DATA_REFRESH_FAILURE.key)
    }
}
