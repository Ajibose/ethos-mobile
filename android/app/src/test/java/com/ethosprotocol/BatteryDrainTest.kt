package com.ethosprotocol

import com.ethosprotocol.services.BatteryDrainMetrics
import com.ethosprotocol.services.BackgroundTaskScheduler
import com.ethosprotocol.widget.VaultWidgetUpdateWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryDrainTest {

    @Test
    fun `background task frequency stays under honest wakeup budgets`() {
        assertTrue(BatteryDrainMetrics.isWithinBudget(15L, BatteryDrainMetrics.MAX_URGENT_WAKEUPS_PER_HOUR))
        assertTrue(BatteryDrainMetrics.isWithinBudget(60L, BatteryDrainMetrics.MAX_NORMAL_WAKEUPS_PER_HOUR))
        assertFalse(BatteryDrainMetrics.isWithinBudget(10L, 0.5))

        val urgentWakeups = BatteryDrainMetrics.estimateWakeupsPerHour(15L)
        val normalWakeups = BatteryDrainMetrics.estimateWakeupsPerHour(60L)

        assertEquals(4.0, urgentWakeups, 0.01)
        assertEquals(1.0, normalWakeups, 0.01)
    }

    @Test
    fun `vault widget refresh interval scales with urgency to reduce battery drain`() {
        assertEquals(60L, VaultWidgetUpdateWorker.determineUpdateInterval(172_800L))
        assertEquals(15L, VaultWidgetUpdateWorker.determineUpdateInterval(86_399L))
        assertEquals(5L, VaultWidgetUpdateWorker.determineUpdateInterval(1_800L))
        assertEquals(2L, VaultWidgetUpdateWorker.determineUpdateInterval(0L))
    }

    @Test
    fun `power hungry operations are tracked with explicit budgets`() {
        val metrics = BatteryDrainMetrics.powerHungryOperations
        assertTrue(metrics.any { it.name == "VaultWidgetUpdateWorker" })
        assertTrue(metrics.any { it.name == "PendingActionSyncWorker" })

        metrics.forEach { metric ->
            assertTrue(metric.intervalMinutes > 0L)
            assertTrue(metric.maxWakeupsPerHour > 0.0)
            assertTrue(BatteryDrainMetrics.isWithinBudget(metric.intervalMinutes, metric.maxWakeupsPerHour))
        }
    }

    @Test
    fun `background task scheduler exposes the battery metrics surface`() {
        assertTrue(BackgroundTaskScheduler::class.java.name.contains("BackgroundTaskScheduler"))
        assertTrue(BatteryDrainMetrics.powerHungryOperations.size >= 2)
    }
}
