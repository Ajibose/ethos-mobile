package com.ethosprotocol.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ethosprotocol.models.AuthToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Quality tier derived from the active network. Higher tiers allow larger images and page sizes;
 * slower tiers (cellular, 2G) trade fidelity for responsiveness.
 */
enum class NetworkQualityTier(val imageQuality: Int, val pageSize: Int) {
    WIFI(imageQuality = 90, pageSize = 30),
    FIVE_G(imageQuality = 85, pageSize = 25),
    FOUR_G(imageQuality = 70, pageSize = 15),
    TWO_G(imageQuality = 50, pageSize = 8)
}

/** User-facing quality preference. [AUTO] defers to the detected network tier. */
enum class QualityPreference {
    AUTO, HIGH, LOW;

    /** Resolves this preference against the detected [tier], honouring explicit overrides. */
    fun resolve(tier: NetworkQualityTier): NetworkQualityTier = when (this) {
        AUTO -> tier
        HIGH -> NetworkQualityTier.WIFI
        LOW -> NetworkQualityTier.TWO_G
    }
}

@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext private val context: Context) {
    val isConnected: Boolean
        get() {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    /** Detects the active network and maps it to a [NetworkQualityTier]. */
    fun currentTier(): NetworkQualityTier {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return NetworkQualityTier.TWO_G
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkQualityTier.TWO_G
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkQualityTier.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> cellularTier(caps)
            else -> NetworkQualityTier.FOUR_G
        }
    }

    private fun cellularTier(caps: NetworkCapabilities): NetworkQualityTier = when {
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> NetworkQualityTier.WIFI
        caps.linkDownstreamBandwidthKbps >= 20_000 -> NetworkQualityTier.FIVE_G
        caps.linkDownstreamBandwidthKbps >= 2_000 -> NetworkQualityTier.FOUR_G
        else -> NetworkQualityTier.TWO_G
    }
}

/**
 * Resolves the effective [NetworkQualityTier] by combining the user's [QualityPreference] with the
 * network detected by [NetworkMonitor]. Persists the preference so it survives process restarts.
 */
@Singleton
class NetworkQualityManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkMonitor: NetworkMonitor
) {
    companion object {
        private const val PREFS = "network_quality"
        private const val KEY_PREFERENCE = "quality_preference"
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** The user's stored preference, defaulting to [QualityPreference.AUTO]. */
    var preference: QualityPreference
        get() = runCatching {
            QualityPreference.valueOf(prefs.getString(KEY_PREFERENCE, null) ?: QualityPreference.AUTO.name)
        }.getOrDefault(QualityPreference.AUTO)
        set(value) {
            prefs.edit().putString(KEY_PREFERENCE, value.name).apply()
        }

    /** The tier to use right now, after applying the user's preference over the detected network. */
    fun effectiveTier(): NetworkQualityTier = preference.resolve(networkMonitor.currentTier())

    /** Image quality (0-100) to request for the current effective tier. */
    fun imageQuality(): Int = effectiveTier().imageQuality

    /** API pagination limit to request for the current effective tier. */
    fun pageSize(): Int = effectiveTier().pageSize
}

/**
 * Sliding-window token refresh. On each successful authenticated request the session's expiry
 * is extended, so active users are not logged out mid-session. A refresh is only attempted once
 * per [refreshIntervalMs] (default 5 minutes) to avoid hammering the auth endpoint. If the
 * refresh token itself has expired, the session is cleared and the caller is told to re-auth.
 */
@Singleton
class TokenRefreshManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "TokenRefresh"
        const val DEFAULT_REFRESH_INTERVAL_MS = 5 * 60 * 1000L
        private const val PREFS = "token_refresh"
        private const val KEY_LAST_REFRESH = "last_refresh_at"
    }

    /** Minimum time between sliding-window refreshes. Configurable for tests. */
    internal var refreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS

    /** How far past the current expiry a successful refresh extends the session. */
    internal var extensionMs: Long = DEFAULT_REFRESH_INTERVAL_MS

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Called after a successful authenticated request. Extends [token]'s expiry when the sliding
     * window allows a refresh. Returns the (possibly extended) token, or null when the refresh
     * token has expired and the session must be re-established.
     */
    fun onSuccessfulRequest(token: AuthToken, now: Instant = Instant.now()): AuthToken? {
        if (isRefreshTokenExpired(token, now)) {
            Log.w(TAG, "Refresh token expired; clearing session")
            clearSession()
            return null
        }
        if (!shouldRefresh(now)) return token
        val extended = extendExpiry(token, now)
        prefs.edit().putLong(KEY_LAST_REFRESH, now.toEpochMilli()).apply()
        return extended
    }

    /** True when enough time has elapsed since the last sliding-window refresh. */
    fun shouldRefresh(now: Instant = Instant.now()): Boolean {
        val last = prefs.getLong(KEY_LAST_REFRESH, 0L)
        return now.toEpochMilli() - last >= refreshIntervalMs
    }

    /** Extends the token's expiry by [extensionMs] from [now]. */
    fun extendExpiry(token: AuthToken, now: Instant = Instant.now()): AuthToken {
        val newExpiry = now.plusMillis(extensionMs)
        return token.copy(expiresAt = newExpiry)
    }

    /** True when the refresh token is missing or already past its expiry. */
    fun isRefreshTokenExpired(token: AuthToken, now: Instant = Instant.now()): Boolean {
        val refreshExpiry = token.refreshExpiresAt ?: return false
        return !refreshExpiry.isAfter(now)
    }

    /** Clears the sliding-window bookkeeping so the next session starts fresh. */
    fun clearSession() {
        prefs.edit().remove(KEY_LAST_REFRESH).apply()
    }
}

/**
 * Validates security-relevant HTTP response headers so the app can detect header-stripping
 * attacks (e.g. a proxy or MITM silently dropping hardening headers). Missing or invalid
 * headers are logged via [SecurityHeaderTelemetry] and surfaced to callers as a report.
 */
object SecurityHeaderValidator {
    private const val TAG = "SecurityHeaders"

    const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
    const val STRICT_TRANSPORT_SECURITY = "Strict-Transport-Security"
    const val X_FRAME_OPTIONS = "X-Frame-Options"

    private val VALID_FRAME_OPTIONS = setOf("DENY", "SAMEORIGIN")

    /** Result of validating a single security header. */
    data class HeaderResult(val name: String, val valid: Boolean, val reason: String?)

    /** Aggregate report for a response's security headers. */
    data class Report(val results: List<HeaderResult>) {
        val isValid: Boolean get() = results.all { it.valid }
        val invalidHeaders: List<HeaderResult> get() = results.filter { !it.valid }
    }

    /**
     * Validates the security headers on [headers]. Header lookup is case-insensitive since HTTP
     * header names are not case-sensitive. Every missing/invalid header is logged.
     */
    fun validate(headers: Map<String, String>): Report {
        val normalized = headers.entries.associate { it.key.lowercase() to it.value }
        val results = listOf(
            validateContentTypeOptions(normalized),
            validateStrictTransportSecurity(normalized),
            validateFrameOptions(normalized)
        )
        results.filter { !it.valid }.forEach { result ->
            Log.w(TAG, "Invalid security header ${result.name}: ${result.reason}")
            SecurityHeaderTelemetry.recordInvalid(result.name)
        }
        return Report(results)
    }

    private fun validateContentTypeOptions(headers: Map<String, String>): HeaderResult {
        val value = headers[X_CONTENT_TYPE_OPTIONS.lowercase()]
        return when {
            value == null -> HeaderResult(X_CONTENT_TYPE_OPTIONS, false, "missing")
            value.trim().equals("nosniff", ignoreCase = true) -> HeaderResult(X_CONTENT_TYPE_OPTIONS, true, null)
            else -> HeaderResult(X_CONTENT_TYPE_OPTIONS, false, "expected 'nosniff' but was '$value'")
        }
    }

    private fun validateStrictTransportSecurity(headers: Map<String, String>): HeaderResult {
        val value = headers[STRICT_TRANSPORT_SECURITY.lowercase()]
        if (value == null) return HeaderResult(STRICT_TRANSPORT_SECURITY, false, "missing")
        val maxAge = Regex("max-age\\s*=\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.get(1)?.toLongOrNull()
            ?: return HeaderResult(STRICT_TRANSPORT_SECURITY, false, "missing or invalid max-age")
        return if (maxAge > 0) {
            HeaderResult(STRICT_TRANSPORT_SECURITY, true, null)
        } else {
            HeaderResult(STRICT_TRANSPORT_SECURITY, false, "max-age must be greater than 0")
        }
    }

    private fun validateFrameOptions(headers: Map<String, String>): HeaderResult {
        val value = headers[X_FRAME_OPTIONS.lowercase()]
        return when {
            value == null -> HeaderResult(X_FRAME_OPTIONS, false, "missing")
            value.trim().uppercase() in VALID_FRAME_OPTIONS -> HeaderResult(X_FRAME_OPTIONS, true, null)
            else -> HeaderResult(X_FRAME_OPTIONS, false, "expected DENY or SAMEORIGIN but was '$value'")
        }
    }
}

/** Tracks counts of invalid/missing security headers for debug/support diagnostics. */
object SecurityHeaderTelemetry {
    private val _invalidCounts = java.util.co

/* … truncated 3221 chars — edit only what you need near the top … */
