package com.ethosprotocol.services

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules background work for the app.
 *
 * Also owns the periodic flush of batched analytics events so that queued
 * session/screen/action events are dispatched even when the app is not in
 * the foreground.
 */
class BackgroundTaskScheduler(private val context: Context) {

    fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }

    /**
     * Schedules the periodic flush of batched analytics events.
     *
     * Events are batched in [SessionAnalytics] and dispatched on this cadence
     * (or earlier when the batch size threshold is reached).
     */
    fun scheduleAnalyticsFlush() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val flushRequest = PeriodicWorkRequestBuilder<AnalyticsFlushWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ANALYTICS_FLUSH_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            flushRequest
        )
    }

    companion object {
        private const val SYNC_WORK_NAME = "ethos_periodic_sync"
        private const val ANALYTICS_FLUSH_WORK_NAME = "ethos_analytics_flush"
    }
}
