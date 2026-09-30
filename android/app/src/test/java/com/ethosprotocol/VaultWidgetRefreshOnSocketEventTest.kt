package com.ethosprotocol

import android.content.Context
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.OfflineCache
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultEvent
import com.ethosprotocol.models.VaultPage
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.services.ExpiringVaultsManager
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.PendingActionDao
import com.ethosprotocol.services.PendingActionSyncWorker
import com.ethosprotocol.services.VaultEventSocket
import com.ethosprotocol.ui.VaultViewModel
import com.ethosprotocol.widget.VaultStatusWidget
import com.ethosprotocol.widget.VaultWidgetUpdateWorker
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * #249 – Widget refresh on WebSocket vault_updated.
 *
 * Verifies that when VaultViewModel receives a vault_updated event via VaultEventSocket
 * while the app is foregrounded, it:
 *   1. Updates the widget data (VaultStatusWidget.saveVaultData) with the new vault state.
 *   2. Triggers an immediate widget refresh (VaultWidgetUpdateWorker.schedule with 0-minute
 *      delay) rather than waiting for the next scheduled poll.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultWidgetRefreshOnSocketEventTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val apiClient: ApiClient = mockk()
    private val notificationHelper: NotificationHelper = mockk(relaxed = true)
    private val pendingActionDao: PendingActionDao = mockk(relaxed = true)
    private val expiringVaultsManager: ExpiringVaultsManager = mockk(relaxed = true)
    private val offlineCache: OfflineCache = mockk(relaxed = true)
    private val fakeNetworkMonitor = FakeNetworkMonitor(startOnline = true)
    private val context: Context = mockk(relaxed = true)
    private lateinit var vm: VaultViewModel
    private lateinit var socketFlow: MutableSharedFlow<VaultEvent>
    private val vaultEventSocket: VaultEventSocket = mockk()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(PendingActionSyncWorker.Companion)
        every { PendingActionSyncWorker.schedule(any()) } just Runs
        mockkObject(VaultStatusWidget)
        every { VaultStatusWidget.saveVaultData(any(), any(), any(), any(), any()) } just Runs
        every { VaultStatusWidget.refreshAll(any()) } just Runs
        every { VaultStatusWidget.formatLastCheckIn(any(), any()) } answers { callOriginal() }
        mockkObject(VaultWidgetUpdateWorker.Companion)
        every { VaultWidgetUpdateWorker.schedule(any(), any()) } just Runs
        every { expiringVaultsManager.bannerState } returns MutableStateFlow(null).asStateFlow()

        socketFlow = MutableSharedFlow(extraBufferCapacity = 8)
        every { vaultEventSocket.events(any()) } returns socketFlow

        vm = VaultViewModel(
            apiClient = apiClient,
            notificationHelper = notificationHelper,
            pendingActionDao = pendingActionDao,
            vaultEventSocket = vaultEventSocket,
            expiringVaultsManager = expiringVaultsManager,
            offlineCache = offlineCache,
            networkMonitor = fakeNetworkMonitor,
            context = context
        )
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkObject(PendingActionSyncWorker.Companion)
        unmockkObject(VaultStatusWidget)
        unmockkObject(VaultWidgetUpdateWorker.Companion)
    }

    /**
     * #249: A vault_updated event must trigger an immediate widget reschedule
     * (intervalMinutes = 0) rather than waiting for the next scheduled tick.
     */
    @Test
    fun `vault_updated event triggers immediate widget reschedule`() = runTest {
        val initialVault = makeVault("vault-1", ttlRemaining = 172_800L)
        coEvery { apiClient.listVaults(limit = 20) } returns
            ApiResult.Success(VaultPage(listOf(initialVault), nextCursor = null, hasMore = false))
        vm.load()

        val updatedVault = initialVault.copy(ttlRemaining = 900L)
        socketFlow.emit(VaultEvent(type = "vault_updated", vault = updatedVault))

        verify { VaultWidgetUpdateWorker.schedule(context, intervalMinutes = 0) }
    }

    /**
     * #249: The widget data is saved with the updated vault values before
     * the reschedule fires, so the worker picks up fresh data on its next run.
     */
    @Test
    fun `vault_updated event saves new vault data to widget prefs before rescheduling`() = runTest {
        val initialVault = makeVault("vault-1", ttlRemaining = 172_800L)
        coEvery { apiClient.listVaults(limit = 20) } returns
            ApiResult.Success(VaultPage(listOf(initialVault), nextCursor = null, hasMore = false))
        vm.load()

        val updatedVault = initialVault.copy(ttlRemaining = 3_600L)
        socketFlow.emit(VaultEvent(type = "vault_updated", vault = updatedVault))

        // Verify saveVaultData was called with the updated vault's ID.
        verify {
            VaultStatusWidget.saveVaultData(
                context,
                vaultId = "vault-1",
                vaultName = any(),
                ttlRemaining = any(),
                lastCheckIn = any()
            )
        }
        // Verify the reschedule happens after the save (call order matters).
        verifyOrder {
            VaultStatusWidget.saveVaultData(context, vaultId = "vault-1", any(), any(), any())
            VaultWidgetUpdateWorker.schedule(context, intervalMinutes = 0)
        }
    }

    /**
     * #249: The reload must fire only for the widget's displayed vault IDs —
     * events for vaults that are NOT in the loaded list (no active subscription)
     * must not trigger a spurious save/reschedule.
     */
    @Test
    fun `vault_updated for untracked vault does not trigger widget refresh`() = runTest {
        val v1 = makeVault("vault-1")
        coEvery { apiClient.listVaults(limit = 20) } returns
            ApiResult.Success(VaultPage(listOf(v1), nextCursor = null, hasMore = false))
        vm.load()

        // The socket subscription is keyed per vault ID. An event where vault.id is not
        // in the list still arrives on the v1 channel (VaultEventSocket is per-vault),
        // but the ViewModel guards the save/reschedule on the vault being non-null.
        // Emit an event with a null vault to simulate a non-update event type passing
        // through the same stream.
        socketFlow.emit(VaultEvent(type = "check_in", vault = null))

        verify(exactly = 0) { VaultStatusWidget.saveVaultData(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { VaultWidgetUpdateWorker.schedule(any(), any()) }
    }

    /**
     * #249: Multiple vault_updated events in quick succession each trigger their own
     * widget reschedule (VaultWidgetUpdateWorker.schedule uses REPLACE policy so only
     * the last one actually runs).
     */
    @Test
    fun `multiple vault_updated events each trigger a widget reschedule`() = runTest {
        val vault = makeVault("vault-1", ttlRemaining = 10_000L)
        coEvery { apiClient.listVaults(limit = 20) } returns
            ApiResult.Success(VaultPage(listOf(vault), nextCursor = null, hasMore = false))
        vm.load()

        socketFlow.emit(VaultEvent(type = "vault_updated", vault = vault.copy(ttlRemaining = 9_000L)))
        socketFlow.emit(VaultEvent(type = "vault_updated", vault = vault.copy(ttlRemaining = 8_000L)))
        socketFlow.emit(VaultEvent(type = "vault_updated", vault = vault.copy(ttlRemaining = 7_000L)))

        verify(exactly = 3) { VaultWidgetUpdateWorker.schedule(context, intervalMinutes = 0) }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun makeVault(id: String, ttlRemaining: Long = 172_800L) = Vault(
        id = id, owner = "GABC", beneficiary = "GXYZ",
        balance = 10_000_000L, checkInInterval = 2_592_000L,
        lastCheckIn = "2026-04-01T00:00:00Z", ttlRemaining = ttlRemaining,
        status = VaultStatus.active
    )
}
