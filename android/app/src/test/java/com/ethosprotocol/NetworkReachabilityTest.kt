package com.ethosprotocol

import android.content.Context
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.OfflineCache
import com.ethosprotocol.models.VaultPage
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.models.Vault
import com.ethosprotocol.services.ConnectionState
import com.ethosprotocol.services.ExpiringVaultsManager
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.PendingActionDao
import com.ethosprotocol.services.PendingActionSyncWorker
import com.ethosprotocol.services.VaultEventSocket
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.ui.VaultViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * #428 — Network reachability callback tests.
 *
 * Verifies that VaultViewModel reacts correctly to connectivity changes:
 *   - isOffline reflects the current network state
 *   - Coming back online after being offline triggers a vault reload
 *   - Coming back online after being offline schedules PendingActionSyncWorker
 *   - Going offline sets isOffline = true without a reload
 *   - Starting online does not trigger a spurious reload
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkReachabilityTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val apiClient: ApiClient = mockk()
    private val notificationHelper: NotificationHelper = mockk(relaxed = true)
    private val pendingActionDao: PendingActionDao = mockk(relaxed = true)
    private val vaultEventSocket: VaultEventSocket = mockk()
    private val expiringVaultsManager: ExpiringVaultsManager = mockk(relaxed = true)
    private val offlineCache: OfflineCache = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private lateinit var fakeNetwork: FakeNetworkMonitor
    private lateinit var vm: VaultViewModel

    private fun makeVault(id: String = "v1") = Vault(
        id = id, owner = "GABC", beneficiary = "GXYZ",
        balance = 10_000_000L, checkInInterval = 2_592_000L,
        lastCheckIn = "2026-01-01T00:00:00Z", ttlRemaining = 86_400L,
        status = VaultStatus.active
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(PendingActionSyncWorker.Companion)
        every { PendingActionSyncWorker.schedule(any()) } just Runs
        every { vaultEventSocket.events(any<String>()) } returns emptyFlow()
        every { vaultEventSocket.events(any<List<String>>()) } returns emptyFlow()
        every { vaultEventSocket.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED).asStateFlow()
        every { expiringVaultsManager.bannerState } returns MutableStateFlow(null).asStateFlow()
        coEvery { apiClient.listVaults(any(), any()) } returns ApiResult.Success(
            VaultPage(vaults = listOf(makeVault()), hasMore = false)
        )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkObject(PendingActionSyncWorker.Companion)
    }

    private fun makeVm(startOnline: Boolean = true): VaultViewModel {
        fakeNetwork = FakeNetworkMonitor(startOnline)
        return VaultViewModel(
            apiClient = apiClient,
            notificationHelper = notificationHelper,
            pendingActionDao = pendingActionDao,
            vaultEventSocket = vaultEventSocket,
            expiringVaultsManager = expiringVaultsManager,
            offlineCache = offlineCache,
            networkMonitor = fakeNetwork,
            context = context
        )
    }

    // ── isOffline state ──────────────────────────────────────────────────────

    @Test
    fun `starts online - isOffline is false`() {
        vm = makeVm(startOnline = true)
        assertFalse(vm.state.value.isOffline)
    }

    @Test
    fun `starts offline - isOffline is true`() {
        vm = makeVm(startOnline = false)
        assertTrue(vm.state.value.isOffline)
    }

    @Test
    fun `going offline sets isOffline true`() {
        vm = makeVm(startOnline = true)
        fakeNetwork.goOffline()
        assertTrue(vm.state.value.isOffline)
    }

    @Test
    fun `coming back online sets isOffline false`() {
        vm = makeVm(startOnline = false)
        fakeNetwork.goOnline()
        assertFalse(vm.state.value.isOffline)
    }

    // ── Reload on reconnect ──────────────────────────────────────────────────

    @Test
    fun `coming back online after offline triggers vault reload`() = runTest {
        vm = makeVm(startOnline = false)
        coVerify(exactly = 0) { apiClient.listVaults(any(), any()) }

        fakeNetwork.goOnline()

        coVerify(atLeast = 1) { apiClient.listVaults(any(), any()) }
    }

    @Test
    fun `starting online does not trigger spurious reload on init`() = runTest {
        vm = makeVm(startOnline = true)
        // No transition from offline→online, so no automatic reload should happen.
        coVerify(exactly = 0) { apiClient.listVaults(any(), any()) }
    }

    @Test
    fun `going offline does not trigger a reload`() = runTest {
        vm = makeVm(startOnline = true)
        fakeNetwork.goOffline()
        coVerify(exactly = 0) { apiClient.listVaults(any(), any()) }
    }

    // ── Sync worker on reconnect ─────────────────────────────────────────────

    @Test
    fun `coming back online schedules PendingActionSyncWorker`() {
        vm = makeVm(startOnline = false)
        verify(exactly = 0) { PendingActionSyncWorker.schedule(any()) }

        fakeNetwork.goOnline()

        verify(exactly = 1) { PendingActionSyncWorker.schedule(any()) }
    }

    @Test
    fun `starting online does not schedule sync worker on init`() {
        vm = makeVm(startOnline = true)
        verify(exactly = 0) { PendingActionSyncWorker.schedule(any()) }
    }

    // ── Idempotency ──────────────────────────────────────────────────────────

    @Test
    fun `toggling online-offline-online triggers reload only on the second online transition`() = runTest {
        vm = makeVm(startOnline = true)

        fakeNetwork.goOffline()
        coVerify(exactly = 0) { apiClient.listVaults(any(), any()) }

        fakeNetwork.goOnline()
        coVerify(exactly = 1) { apiClient.listVaults(any(), any()) }

        // Going offline again without a second reload
        fakeNetwork.goOffline()
        coVerify(exactly = 1) { apiClient.listVaults(any(), any()) }

        // Coming back online a second time triggers a second reload
        fakeNetwork.goOnline()
        coVerify(exactly = 2) { apiClient.listVaults(any(), any()) }
    }
}
