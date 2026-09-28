package com.ethosprotocol

import com.ethosprotocol.api.NetworkMonitor
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Test double for [NetworkMonitor] (#428). Provides a controllable connectivity
 * flow so tests can simulate going offline and coming back online without a real
 * Android ConnectivityManager.
 *
 * Usage:
 *   val fakeNetwork = FakeNetworkMonitor(startOnline = true)
 *   fakeNetwork.goOffline()   // simulate losing connectivity
 *   fakeNetwork.goOnline()    // simulate restoring connectivity
 */
class FakeNetworkMonitor(startOnline: Boolean = true) : NetworkMonitor(mockk(relaxed = true)) {
    private val _connected = MutableStateFlow(startOnline)

    override val isConnected: Boolean get() = _connected.value

    override val connectivityFlow: Flow<Boolean> = _connected.asStateFlow().distinctUntilChanged()

    fun goOnline() { _connected.value = true }
    fun goOffline() { _connected.value = false }
}
