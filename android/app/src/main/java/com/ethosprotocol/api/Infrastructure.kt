package com.ethosprotocol.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ethosprotocol.models.AuthToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import android.util.Base64
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext private val context: Context) {

    /** Snapshot check — true when there is an active network with internet capability. */
    open val isConnected: Boolean
        get() {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    /**
     * Cold [Flow] that emits the current connectivity state immediately on collection,
     * then emits again whenever it changes. Suitable for `collectAsStateWithLifecycle`
     * in Compose UI and for triggering sync on reconnect in ViewModels.
     *
     * Emits `true` when at least one network with [NetworkCapabilities.NET_CAPABILITY_INTERNET]
     * is available, `false` when none is. [distinctUntilChanged] suppresses duplicate emissions
     * (e.g. if two networks overlap during a hand-off).
     */
    open val connectivityFlow: Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }
            override fun onLost(network: Network) {
                // Re-evaluate: there may still be another connected network.
                val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return)
                trySend(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }
        }

        // Emit current state immediately so collectors don't need a separate isConnected check.
        trySend(isConnected)

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)

        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}

// Wraps a cached response with the wall-clock time it was written, so callers can tell how
// stale the data is instead of presenting it as unconditionally "current".
@Serializable
data class CacheEnvelope(val timestamp: Long, val data: String)

@Singleton
class OfflineCache @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir = File(context.cacheDir, "ttl_offline").also { it.mkdirs() }

    // Byte cap for the cache directory's total size; least-recently-used entries are evicted
    // once a save() pushes the directory over this cap. Exposed as `internal var` (rather than
    // a constructor param) so tests can shrink it to force eviction deterministically without
    // fighting Hilt's @Inject constructor resolution.
    internal var maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES

    // Entries older than maxAgeMs are treated as stale by load() — a non-null cachedAt is
    // still returned by load() but also flagged via the CacheEnvelope so the UI can surface
    // "last updated N hours ago" instead of silently serving data. -1L means no expiry enforced.
    internal var maxAgeMs: Long = DEFAULT_MAX_AGE_MS

    // Optional more aggressive TTL (in addition to maxAgeMs) for faster cache invalidation
    // when needed. Set to non-negative value to enable. -1L means no aggressive TTL enforced.
    internal var aggressiveTtlMs: Long = -1L

    // Tracks access recency in-memory (accessOrder = true keeps the most-recently-used entry at
    // the tail on both get and put). Filesystem mtime is deliberately not used for LRU ordering
    // since its resolution varies across filesystems/devices and would make eviction order
    // unpredictable; this only needs to be accurate for the current process's lifetime.
    private val accessOrder = Collections.synchronizedMap(
        object : LinkedHashMap<String, Unit>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>) = false
        }
    )

    init {
        dir.listFiles()?.sortedBy { it.lastModified() }?.forEach { accessOrder[it.name] = Unit }
        // Clean up expired cache entries on app launch
        cleanupExpiredEntries()
    }

    fun save(key: String, json: String) {
        val fileName = key.sha256()
        val envelope = CacheEnvelope(timestamp = System.currentTimeMillis(), data = encodeCachedPayload(json))
        File(dir, fileName).writeText(Json.encodeToString(CacheEnvelope.serializer(), envelope))
        touch(fileName)
        CacheTelemetry.recordWrite(json.length.toLong())
        evictIfNeeded()
    }

    fun load(key: String): CacheEnvelope? {
        val fileName = key.sha256()
        val envelope = runCatching {
            val raw = Json.decodeFromString(CacheEnvelope.serializer(), File(dir, fileName).readText())
            raw.copy(data = decodeCachedPayload(raw.data))
        }.getOrNull()
        if (envelope != null) {
            touch(fileName)
            if (isCachedAtStale(envelope.timestamp)) {
                CacheTelemetry.recordStaleServed()
            } else {
                CacheTelemetry.recordHit()
            }
        } else {
            CacheTelemetry.recordMiss()
        }
        return envelope
    }

    // Wipes every cached entry, e.g. on sign-out so the next user's device doesn't retain a
    // previous account's vault data offline.
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        accessOrder.clear()
    }

    /** Returns true when the given cache timestamp is older than [maxAgeMs] or [aggressiveTtlMs]. */
    fun isCachedAtStale(timestamp: Long): Boolean {
        val now = System.currentTimeMillis()
        val age = now - timestamp
        // Check aggressive TTL first if set
        if (aggressiveTtlMs >= 0 && age > aggressiveTtlMs) return true
        // Then check standard maxAgeMs
        if (maxAgeMs < 0) return false
        return age > maxAgeMs
    }

    /** Cleans up expired cache entries on app launch or manual trigger. */
    fun cleanupExpiredEntries() {
        dir.listFiles()?.forEach { file ->
            runCatching {
                val envelope = Json.decodeFromString(CacheEnvelope.serializer(), file.readText())
                if (isCachedAtStale(envelope.timestamp)) {
                    file.delete()
                    accessOrder.keys.remove(file.name)
                }
            }
        }
    }

    /** Invalidate cache entry for a specific key, typically on manual refresh. */
    fun invalidate(key: String) {
        val fileName = key.sha256()
        File(dir, fileName).delete()
        accessOrder.keys.remove(fileName)
    }

    private fun touch(fileName: String) {
        accessOrder[fileName] = Unit
    }

    private fun evictIfNeeded() {
        var totalSize = dir.listFiles()?.sumOf { it.length() } ?: 0L
        if (totalSize <= maxCacheBytes) return
        val leastRecentlyUsed = synchronized(accessOrder) { accessOrder.keys.toList() }
        for (fileName in leastRecentlyUsed) {
            if (totalSize <= maxCacheBytes) break
            val file = File(dir, fileName)
            if (file.exists()) {
                totalSize -= file.length()
                file.delete()
            }
            accessOrder.keys.remove(fileName)
        }
    }

    private fun encodeCachedPayload(json: String): String =
        Base64.encodeToString(xorWithCacheKey(json.toByteArray()), Base64.NO_WRAP)

    private fun decodeCachedPayload(encoded: String): String =
        runCatching { String(xorWithCacheKey(Base64.decode(encoded, Base64.NO_WRAP))) }.getOrDefault(encoded)

    private fun xorWithCacheKey(bytes: ByteArray): ByteArray {
        val key = MessageDigest.getInstance("SHA-256")
            .digest("${context.packageName}:offline-cache".toByteArray())
        return ByteArray(bytes.size) { index -> (bytes[index].toInt() xor key[index % key.size].toInt()).toByte() }
    }

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_MAX_CACHE_BYTES = 5L * 1024 * 1024 // 5 MB
        const val DEFAULT_MAX_AGE_MS = 24L * 60 * 60 * 1000 // 24 hours
        const val ENABLE_HTTP2_PUSH_CACHE = false
    }
}

/** Tracks offline-cache access statistics for debug/support diagnostics. */
object CacheTelemetry {
    private val _hits = java.util.concurrent.atomic.AtomicLong(0)
    private val _misses = java.util.concurrent.atomic.AtomicLong(0)
    private val _staleServed = java.util.concurrent.atomic.AtomicLong(0)
    private val _bytesWritten = java.util.concurrent.atomic.AtomicLong(0)

    val hits: Long get() = _hits.get()
    val misses: Long get() = _misses.get()
    val staleServed: Long get() = _staleServed.get()
    val bytesWritten: Long get() = _bytesWritten.get()

    fun recordHit() { _hits.incrementAndGet() }
    fun recordMiss() { _misses.incrementAndGet() }
    fun recordStaleServed() { _staleServed.incrementAndGet() }
    fun recordWrite(bytes: Long) { _bytesWritten.addAndGet(bytes.coerceAtLeast(0L)) }

    fun reset() { _hits.set(0); _misses.set(0); _staleServed.set(0); _bytesWritten.set(0) }

    fun snapshot() = CacheTelemetrySnapshot(hits, misses, staleServed, bytesWritten)
}

data class CacheTelemetrySnapshot(val hits: Long, val misses: Long, val staleServed: Long, val bytesWritten: Long)

/**
 * Abstracted so PasskeyServiceTest can supply an in-memory fake without a real
 * Android Context / EncryptedSharedPreferences. [pushToken], [setSession], and
 * [isNearExpiry] get harmless defaults so a minimal fake only needs to implement
 * [token] and [clear] — see [EncryptedTokenProvider] for the real, persisted behavior.
 */
interface TokenProvider {
    var token: String?
    var pushToken: String?
        get() = null
        set(_) {}
    // #234: a push token seen (via onNewToken) but not yet confirmed registered
    // with the server — set when registration fails after retrying, cleared
    // once it succeeds. See PushService's retry-on-foreground.
    var pendingPushToken: String?
        get() = null
        set(_) {}
    fun setSession(authToken: AuthToken) { token = authToken.token }
    fun isNearExpiry(threshold: Duration = Duration.ofSeconds(60)): Boolean = false
    fun clear()
}

@Singleton
class EncryptedTokenProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : TokenProvider {
    // ---------------------------------------------------------------------------
    // Android token-storage accessibility review (task #122) — mirrors the
    // documented rationale in iOS KeychainService.swift (saveToken).
    //
    // Which components need token access and under what conditions?
    //
    //   1. ApiClient (foreground)       — always running while the UI is visible;
    //                                     device is unlocked. Any protection level works.
    //   2. PendingActionSyncWorker (background) — WorkManager task that can run while the
    //                                     device screen is off but the device is NOT
    //                                     locked (WorkManager constraints use CONNECTED
    //                                     only). The device must be unlocked for
    //                                     EncryptedSharedPreferences backed by
    //                                     AES256_GCM (hardware-backed key) to succeed.
    //   3. VaultStatusWidget (AppWidget) — AppWidget update callbacks run on the main
    //                                     process, always while the device is unlocked
    //                                     (AppWidgets are not invoked on a locked screen
    //                                     on Android). The token is only needed here for
    //                                     optional authenticated refresh calls.
    //
    // Conclusion: unlike iOS (where BackgroundRefreshService and TTLWidget can run
    // while the device is still locked, requiring AfterFirstUnlock), no Android
    // component in this app needs token access while the device is locked.
    // EncryptedSharedPreferences with AES256_GCM uses a hardware-backed key that
    // is only available after the user has unlocked the device (equivalent to
    // kSecAttrAccessibleWhenUnlockedThisDeviceOnly on iOS). This IS the least-
    // privileged option that still satisfies all access requirements above — no
    // relaxation (analogous to AfterFirstUnlock) is needed on Android.
    //
    // If a future component (e.g. a background sync that must run while locked)
    // is added, revisit this decision and document the new requirement here.
    // ---------------------------------------------------------------------------
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "ttl_auth_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override var token: String?
        get() = prefs.getString("token", null)
        set(value) = prefs.edit().apply {
            if (value != null) putString("token", value) else remove("token")
        }.apply()

    // The last FCM token this device registered with the backend, so it can be
    // unregistered on sign-out even if Firebase doesn't hand out a fresh token then.
    override var pushToken: String?
        get() = prefs.getString("push_token", null)
        set(value) = prefs.edit().apply {
            if (value != null) putString("push_token", value) else remove("push_token")
        }.apply()

    override var pendingPushToken: String?
        get() = prefs.getString("pending_push_token", null)
        set(value) = prefs.edit().apply {
            if (value != null) putString("pending_push_token", value) else remove("pending_push_token")
        }.apply()

    private var expiresAtEpochMillis: Long?
        get() = prefs.getLong(KEY_EXPIRES_AT, -1L).takeIf { it >= 0 }
        set(value) = prefs.edit().apply {
            if (value != null) putLong(KEY_EXPIRES_AT, value) else remove(KEY_EXPIRES_AT)
        }.apply()

    // Stores both the bearer token and its expiry from an auth response, so ApiClient can
    // proactively refresh before the backend would reject the token with a 401 — previously
    // AuthToken.expiresAt was parsed off the wire and then never read anywhere.
    override fun setSession(authToken: AuthToken) {
        token = authToken.token
        expiresAtEpochMillis = runCatching { Instant.parse(authToken.expiresAt).toEpochMilli() }.getOrNull()
    }

    override fun isNearExpiry(threshold: Duration): Boolean {
        val expiry = expiresAtEpochMillis ?: return false
        return Instant.now().plus(threshold).toEpochMilli() >= expiry
    }

    override fun clear() {
        token = null
        expiresAtEpochMillis = null
    }

    private companion object {
        const val KEY_EXPIRES_AT = "expires_at_epoch_millis"
    }
}
