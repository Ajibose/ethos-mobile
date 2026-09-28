package com.ethosprotocol.models

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Calendar

/**
 * Per-category notification preferences and optional quiet hours.
 * Persisted to SharedPreferences and synced server-side on change so
 * preferences survive reinstall.
 */
@Serializable
data class NotificationPreferences(
    /** Whether TTL-expiry warning notifications are enabled. */
    val ttlWarningsEnabled: Boolean = true,
    /** Whether check-in reminder notifications are enabled. */
    val checkInRemindersEnabled: Boolean = true,
    /** Whether quiet hours are active. */
    val quietHoursEnabled: Boolean = false,
    /** Start of quiet hours (hour 0-23, local time). */
    val quietHoursStart: Int = 22,
    /** End of quiet hours (hour 0-23, local time). */
    val quietHoursEnd: Int = 8,
    /** Whether in-app notification badges are shown. */
    val inAppBadgesEnabled: Boolean = true,
    /** Whether the in-app notification banner is shown. */
    val inAppBannerEnabled: Boolean = true,
    /** Where the in-app notification banner is anchored on screen. */
    val inAppBannerPosition: InAppBannerPosition = InAppBannerPosition.TOP
) {
    /**
     * Returns true if a notification should be suppressed right now based on quiet hours.
     * Mirrors iOS NotificationPreferences.isSuppressedByQuietHours.
     */
    fun isSuppressedByQuietHours(hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): Boolean {
        if (!quietHoursEnabled) return false
        return if (quietHoursStart <= quietHoursEnd) {
            hourOfDay >= quietHoursStart && hourOfDay < quietHoursEnd
        } else {
            // Wraps midnight, e.g. 22:00–08:00
            hourOfDay >= quietHoursStart || hourOfDay < quietHoursEnd
        }
    }

    companion object {
        private const val PREFS_NAME = "notification_preferences"
        private const val KEY = "prefs_json"
        private val json = Json { ignoreUnknownKeys = true }

        fun load(context: Context): NotificationPreferences {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val stored = prefs.getString(KEY, null) ?: return NotificationPreferences()
            return try { json.decodeFromString(stored) } catch (_: Exception) { NotificationPreferences() }
        }

        fun save(context: Context, preferences: NotificationPreferences) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY, json.encodeToString(preferences)).apply()
        }
    }
}

/**
 * Anchor position for the in-app notification banner.
 * Customizable so the banner can be placed where it does not obscure
 * primary content on different screens.
 */
@Serializable
enum class InAppBannerPosition {
    TOP,
    BOTTOM
}
