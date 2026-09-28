package com.ethosprotocol.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.ethosprotocol.R
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.ui.MainActivity
import com.ethosprotocol.utils.DateTimeFormatter
import com.ethosprotocol.utils.WidgetErrorLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue

class VaultStatusWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        widgetIds.forEach { updateWidget(context, manager, it) }
    }

    /** Re-render when the user resizes the widget so the layout adapts to the new size (#247). */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, manager, widgetId, newOptions)
        updateWidget(context, manager, widgetId)
    }

    companion object {
        // Per-widget shared-prefs key pattern (#246). Each widget instance gets its own
        // preferences file so different widgets can show different vaults simultaneously.
        private fun prefsName(widgetId: Int) = "vault_widget_prefs_$widgetId"

        // Shared prefs key used to store the list of all known vault IDs, for use by
        // VaultWidgetConfigActivity's vault picker (#245).
        const val PREFS_SHARED = "vault_widget_shared"
        const val KEY_VAULT_ID_LIST = "vault_id_list"

        private const val KEY_VAULT_ID = "vault_id"
        private const val KEY_VAULT_NAME = "vault_name"
        private const val KEY_TTL = "ttl_remaining"
        private const val KEY_LAST_CHECK_IN = "last_check_in"
        const val KEY_BALANCE = "balance"
        const val KEY_BENEFICIARY = "beneficiary"

        // Selected-vault key stored in per-widget prefs; written by VaultWidgetConfigActivity.
        private const val KEY_SELECTED_VAULT_ID = "selected_vault_id"

        /**
         * Saves vault display data to per-widget SharedPreferences (#246).
         * Each widget ID maps to its own prefs file so data is isolated per instance.
         */
        fun saveVaultData(
            context: Context,
            widgetId: Int,
            vaultId: String,
            vaultName: String,
            ttlRemaining: String,
            lastCheckIn: String,
            balance: String,
            beneficiary: String
        ) {
            context.getSharedPreferences(prefsName(widgetId), Context.MODE_PRIVATE).edit()
                .putString(KEY_VAULT_ID, vaultId)
                .putString(KEY_VAULT_NAME, vaultName)
                .putString(KEY_TTL, ttlRemaining)
                .putString(KEY_LAST_CHECK_IN, lastCheckIn)
                .putString(KEY_BALANCE, balance)
                .putString(KEY_BENEFICIARY, beneficiary)
                .apply()
        }

        /**
         * Returns the vault ID pinned by the user for this widget instance via
         * VaultWidgetConfigActivity, or null if the user has not made a selection (#245 / #246).
         */
        fun getSelectedVaultId(context: Context, widgetId: Int): String? =
            context.getSharedPreferences(prefsName(widgetId), Context.MODE_PRIVATE)
                .getString(KEY_SELECTED_VAULT_ID, null)
                .takeIf { !it.isNullOrEmpty() }

        /**
         * Persists the user's vault selection for a specific widget instance (#245 / #246).
         * Called by VaultWidgetConfigActivity when the user picks a vault.
         */
        fun saveSelectedVaultId(context: Context, widgetId: Int, vaultId: String) {
            context.getSharedPreferences(prefsName(widgetId), Context.MODE_PRIVATE).edit()
                .putString(KEY_SELECTED_VAULT_ID, vaultId)
                .apply()
        }

        /**
         * Chooses the correct layout resource based on the widget's current width (#247).
         * Reads OPTION_APPWIDGET_MIN_WIDTH from the options bundle:
         *   width < 180dp  → small  (TTL only)
         *   180 ≤ width < 250dp → medium (TTL + balance)
         *   width ≥ 250dp  → large  (TTL + balance + beneficiary)
         */
        fun selectLayout(options: Bundle): Int {
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            return when {
                minWidth >= 250 -> R.layout.vault_widget_large
                minWidth >= 180 -> R.layout.vault_widget_medium
                else -> R.layout.vault_widget_small
            }
        }

        /** Builds the deep link used to open a widget tap directly onto the vault it displayed. */
        internal fun deepLinkUri(vaultId: String): String = "ethosprotocol://vault/$vaultId/view-details"

        fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val prefs = context.getSharedPreferences(prefsName(widgetId), Context.MODE_PRIVATE)
            val vaultId = prefs.getString(KEY_VAULT_ID, null)
            val vaultName = prefs.getString(KEY_VAULT_NAME, "—") ?: "—"
            val ttl = prefs.getString(KEY_TTL, context.getString(R.string.widget_ttl_unknown)) ?: context.getString(R.string.widget_ttl_unknown)
            val lastCheckIn = prefs.getString(KEY_LAST_CHECK_IN, context.getString(R.string.widget_last_checkin_never)) ?: context.getString(R.string.widget_last_checkin_never)
            val balance = prefs.getString(KEY_BALANCE, "—") ?: "—"
            val beneficiary = prefs.getString(KEY_BENEFICIARY, "—") ?: "—"

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (!vaultId.isNullOrEmpty()) {
                    data = Uri.parse(deepLinkUri(vaultId))
                }
            }
            val pendingIntent = PendingIntent.getActivity(
                context, widgetId, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Pick layout based on current widget size options (#247).
            val options = manager.getAppWidgetOptions(widgetId)
            val layoutId = selectLayout(options)

            // ---------------------------------------------------------------------------
            // #439: Widget Dark Mode Support
            //
            // Android supports widget dark mode via night-mode resource qualifiers.
            // The recommended approach is:
            //   res/layout/vault_widget.xml       — light-mode layout (hardcoded light colours)
            //   res/layout-night/vault_widget.xml — dark-mode layout (dark background + light text)
            //
            // At runtime, Android selects the appropriate layout automatically based on the
            // current UI mode, so RemoteViews built from R.layout.vault_widget will already
            // pick up the night variant when the device is in dark mode.
            //
            // If vault_widget.xml currently uses hardcoded colours (e.g. #FF1C1C1E background,
            // white text), add res/layout-night/vault_widget.xml with dark-surface colours
            // (e.g. #FF2C2C2E background, #EBEBF5 text) and the same view IDs so the code
            // below works unchanged.
            //
            // As a runtime fallback, we also detect dark mode here and can apply
            // RemoteViews.setColorAttr / setInt overrides for fine-grained control.
            // ---------------------------------------------------------------------------
            val isDarkMode = context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

            val views = RemoteViews(context.packageName, layoutId).apply {
                setTextViewText(R.id.widget_vault_name, vaultName)
                setTextViewText(R.id.widget_ttl, "TTL: $ttl")
                // Medium and large layouts include balance / beneficiary views.
                // setTextViewText on a view that doesn't exist in the current layout is a no-op
                // for RemoteViews, so these calls are safe across all layout sizes.
                setTextViewText(R.id.widget_balance, balance)
                setTextViewText(R.id.widget_beneficiary, beneficiary)
                setOnClickPendingIntent(R.id.widget_root, pendingIntent)

                // #439: If vault_widget.xml does NOT yet have a res/layout-night/ variant,
                // apply programmatic dark/light overrides here.  These mirror the colours in
                // WidgetScreenshotTest (VaultWidgetPreview):
                //   Dark  background : 0xFF1C1C1E  (matches existing XML dark bg)
                //   Light background : 0xFFFFFFFF  (standard light surface)
                //   Dark  text       : 0xFFEBEBF5  (iOS-aligned off-white)
                //   Light text       : 0xFF1C1C1E  (dark label)
                //
                // Remove these overrides once res/layout-night/vault_widget.xml is added and
                // the XML handles colour selection automatically via night-mode qualifiers.
                if (isDarkMode) {
                    setInt(R.id.widget_root, "setBackgroundColor", 0xFF1C1C1E.toInt())
                } else {
                    setInt(R.id.widget_root, "setBackgroundColor", android.graphics.Color.WHITE)
                }
            }
            manager.updateAppWidget(widgetId, views)
        }

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, VaultStatusWidget::class.java))
            ids.forEach { updateWidget(context, manager, it) }
        }

        /** Formats an ISO-8601 timestamp as a relative time ("2 hours ago") for widget display. */
        internal fun formatLastCheckIn(isoTimestamp: String, context: Context, now: Instant = Instant.now()): String {
            val checkInInstant = runCatching { Instant.parse(isoTimestamp) }.getOrNull() ?: return isoTimestamp
            val seconds = Duration.between(checkInInstant, now).seconds.coerceAtLeast(0)
            return when {
                seconds < 60 -> context.getString(R.string.widget_last_checkin_just_now)
                seconds < 3_600 -> relative(context, seconds / 60, "minute")
                seconds < 86_400 -> relative(context, seconds / 3_600, "hour")
                else -> relative(context, seconds / 86_400, "day")
            }
        }

        private fun relative(context: Context, value: Long, unit: String): String {
            val plural = if (value == 1L) "" else "s"
            return when (unit) {
                "minute" -> context.getString(R.string.widget_last_checkin_minutes, value, plural)
                "hour" -> context.getString(R.string.widget_last_checkin_hours, value, plural)
                "day" -> context.getString(R.string.widget_last_checkin_days, value, plural)
                else -> "$value $unit$plural ago"
            }
        }
    }
}

@HiltWorker
class VaultWidgetUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val apiClient: ApiClient
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = apiClient.listVaults()
        if (result is ApiResult.Success) {
            val vaults = result.data.filter { it.status == VaultStatus.active }

            // #438: Log a data-refresh failure when the API succeeds but returns no
            // active vaults — the call worked but the widget has nothing useful to show.
            if (vaults.isEmpty()) {
                WidgetErrorLogger.logDataRefreshFailure(
                    "Data refresh returned no active vaults (total: ${result.data.size})"
                )
                return Result.success()
            }

            // Pick the active vault with the lowest ttlRemaining as the urgency fallback.
            // Individual widget instances may override this with a pinned vault ID (#245/#246).
            val urgentVault = vaults.minByOrNull { it.ttlRemaining ?: Long.MAX_VALUE }
                ?: return Result.success()

            // Save the full vault ID list so VaultWidgetConfigActivity can populate
            // the picker (#245).
            val vaultIdList = vaults.joinToString(",") { it.id }
            applicationContext.getSharedPreferences(
                VaultStatusWidget.PREFS_SHARED, Context.MODE_PRIVATE
            ).edit().putString(VaultStatusWidget.KEY_VAULT_ID_LIST, vaultIdList).apply()

            // Update each widget instance independently (#246).
            // If the user has pinned a specific vault, use that; otherwise fall back to urgentVault.
            val manager = AppWidgetManager.getInstance(applicationContext)
            val ids = manager.getAppWidgetIds(
                ComponentName(applicationContext, VaultStatusWidget::class.java)
            )
            ids.forEach { widgetId ->
                val pinnedId = VaultStatusWidget.getSelectedVaultId(applicationContext, widgetId)
                val vault = if (pinnedId != null) {
                    vaults.find { it.id == pinnedId } ?: urgentVault
                } else {
                    urgentVault
                }
                VaultStatusWidget.saveVaultData(
                    applicationContext,
                    widgetId = widgetId,
                    vaultId = vault.id,
                    vaultName = vault.id.take(12) + "…",
                    ttlRemaining = formatTtl(applicationContext, vault.ttlRemaining),
                    lastCheckIn = VaultStatusWidget.formatLastCheckIn(vault.lastCheckIn, applicationContext),
                    balance = vault.formattedBalance,
                    beneficiary = vault.beneficiary.take(12) + "…"
                )
                VaultStatusWidget.updateWidget(applicationContext, manager, widgetId)
            }

            schedule(applicationContext, determineUpdateInterval(urgentVault.ttlRemaining))
        } else {
            // #438: Log a load failure for any non-Success result (network unavailable
            // or API error) so it surfaces in Logcat and Firebase Performance.
            val reason = when (result) {
                is ApiResult.NetworkUnavailable -> "Network unavailable"
                is ApiResult.Error -> "API error ${result.code}: ${result.message}"
                else -> result::class.simpleName ?: "Unknown error"
            }
            WidgetErrorLogger.logLoadFailure(reason)
        }
        return Result.success()
    }

    private fun formatTtl(context: Context, seconds: Long?): String {
        if (seconds == null) return context.getString(R.string.widget_ttl_unknown)
        return DateTimeFormatter.formatDurationInSeconds(seconds)
    }

    companion object {
        const val WORK_NAME = "vault_widget_update"

        // WorkManager enforces a 15-minute floor on periodic work, so that's the shortest
        // interval available for a vault close to expiring. Once it's not urgent, back off
        // to a much longer interval to conserve battery (coordinated with iOS's #33 gap).
        private const val URGENT_INTERVAL_MINUTES = 15L
        private const val NORMAL_INTERVAL_MINUTES = 60L
        private const val URGENCY_THRESHOLD_SECONDS = 86_400L // 24h, matches Vault.isExpiringSoon

        /** Picks the widget refresh interval based on how close the most urgent vault is to expiring. */
        internal fun determineUpdateInterval(ttlRemainingSeconds: Long?): Long =
            if ((ttlRemainingSeconds ?: Long.MAX_VALUE) < URGENCY_THRESHOLD_SECONDS) {
                URGENT_INTERVAL_MINUTES
            } else {
                NORMAL_INTERVAL_MINUTES
            }

        fun schedule(context: Context, intervalMinutes: Long = NORMAL_INTERVAL_MINUTES) {
            val request = PeriodicWorkRequestBuilder<VaultWidgetUpdateWorker>(intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
