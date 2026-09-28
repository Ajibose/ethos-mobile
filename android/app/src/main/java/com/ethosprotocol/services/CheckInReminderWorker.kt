package com.ethosprotocol.services

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Posts a check-in reminder that [NotificationHelper.scheduleCheckInReminder] timed against the
 * vault's TTL (#197). Scheduling lives in NotificationHelper; this worker only delivers.
 *
 * Also records the pending check-in so the in-app notification badge (#421) can surface a
 * count of reminders the user has not yet acted on.
 */
@HiltWorker
class CheckInReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val notificationHelper: NotificationHelper,
    private val pendingCheckInStore: PendingCheckInStore
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val vaultId = inputData.getString(KEY_VAULT_ID) ?: return Result.success()
        val title = inputData.getString(KEY_TITLE) ?: return Result.success()
        val body = inputData.getString(KEY_BODY) ?: return Result.success()
        pendingCheckInStore.add(vaultId)
        notificationHelper.show(title, body, vaultId)
        return Result.success()
    }

    companion object {
        const val KEY_VAULT_ID = "vault_id"
        const val KEY_TITLE = "title"
        const val KEY_BODY = "body"
    }
}
