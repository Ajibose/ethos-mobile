package com.ethosprotocol.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * Network quality tiers used to adapt image quality and API pagination.
 * Ordered from best (WIFI) to worst (SLOW_2G).
 */
enum class NetworkQualityTier {
    WIFI,
    CELLULAR_5G,
    CELLULAR_4G,
    CELLULAR_2G;

    /** Relative image quality (0..100) to request for this tier. */
    val imageQuality: Int
        get() = when (this) {
            WIFI -> 100
            CELLULAR_5G -> 85
            CELLULAR_4G -> 65
            CELLULAR_2G -> 40
        }

    /** Page size to use for paginated API requests on this tier. */
    val pageSize: Int
        get() = when (this) {
            WIFI -> 50
            CELLULAR_5G -> 40
            CELLULAR_4G -> 25
            CELLULAR_2G -> 10
        }

    val isCellular: Boolean
        get() = this != WIFI
}

/**
 * User-facing quality preference. AUTO defers to detected network conditions;
 * HIGH / LOW override automatic detection.
 */
enum class QualityPreference {
    AUTO,
    HIGH,
    LOW;

    /**
     * Resolve the effective tier given the detected network tier.
     * HIGH forces WiFi-grade quality, LOW forces the most conservative tier.
     */
    fun resolve(detected: NetworkQualityTier): NetworkQualityTier = when (this) {
        AUTO -> detected
        HIGH -> NetworkQualityTier.WIFI
        LOW -> NetworkQualityTier.CELLULAR_2G
    }
}

/**
 * Detects the current network type and maps it to a [NetworkQualityTier].
 */
object NetworkQualityDetector {

    fun detect(context: Context): NetworkQualityTier {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkQualityTier.CELLULAR_4G
        val network = cm.activeNetwork ?: return NetworkQualityTier.CELLULAR_2G
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkQualityTier.CELLULAR_2G

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) {
            return NetworkQualityTier.WIFI
        }

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ->
                    NetworkQualityTier.CELLULAR_5G
                caps.linkDownstreamBandwidthKbps >= 10_000 -> NetworkQualityTier.CELLULAR_5G
                caps.linkDownstreamBandwidthKbps >= 2_000 -> NetworkQualityTier.CELLULAR_4G
                else -> NetworkQualityTier.CELLULAR_2G
            }
        }

        return NetworkQualityTier.CELLULAR_4G
    }

    /**
     * Resolve the effective tier honoring the user's [QualityPreference].
     */
    fun resolve(context: Context, preference: QualityPreference): NetworkQualityTier =
        preference.resolve(detect(context))
}
