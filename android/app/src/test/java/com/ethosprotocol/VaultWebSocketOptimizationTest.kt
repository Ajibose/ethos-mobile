package com.ethosprotocol

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.OfflineCache
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultEvent
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.services.ExpiringVaultsManager
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.PendingActionDao
import com.ethosprotocol.services.VaultEventSocket
import com.ethosprotocol.ui.VaultViewModel
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies that single-vault WebSocket updates are handled efficiently without
 * triggering redundant full-list refreshes (#320).
 *
 * When a vault_updated event arrives for a specific vault, the ViewModel should:
 * - Update only that vault in local state via updateVaultInPlace()
 * - NOT trigger a full listVaults() API call
 * - NOT reset pagination cursors
 *
 * This optimization significantly reduces network traffic and battery usage when
 * multiple vault_updated events arrive (e.g., concurrent deposits, check-ins).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class VaultWebSocketOptimizationTest {

    private val apiClient: ApiClient = mockk()
    private val notificationHelper: NotificationHelper = mockk(relaxed = true)
    private val pendingActionDao: PendingActionDao = mockk(relaxed = true)
    private val vaultEventSocket: VaultEventSocket = mockk()
    private val expiringVaultsManager: ExpiringVaultsManager = mockk(relaxed = true)
    private val offlineCache: OfflineCache = mockk(relaxed = true)
    private val fakeNetworkMonitor = FakeNetworkMonitor(startOnline = true)
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        every { expiringVaultsManager.bannerState } returns MutableStateFlow(null).asStateFlow()
    }

    private fun makeVm() = VaultViewModel(
        apiClient = apiClient,
        notificationHelper = notificationHelper,
        pendingActionDao = pendingActionDao,
        vaultEventSocket = vaultEventSocket,
        expiringVaultsManager = expiringVaultsManager,
        offlineCache = offlineCache,
        networkMonitor = fakeNetworkMonitor,
        context = context
    )

    @Test
    fun `single vault WebSocket event updates vault in place without full refetch`() = runTest {
        val vault1 = Vault(
            id = "vault-1", owner = "GABC", beneficiary = "GXYZ",
            balance = 10_000_000L, checkInInterval = 2_592_000L,
            lastCheckIn = "2026-04-01T00:00:00Z", ttlRemaining = 172_800L,
            status = VaultStatus.active
        )
        val vault2 = Vault(
            id = "vault-2", owner = "GABC", beneficiary = "GXYZ",
            balance = 20_000_000L, checkInInterval = 2_592_000L,
            lastCheckIn = "2026-04-01T00:00:00Z", ttlRemaining = 172_800L,
            status = VaultStatus.active
        )

        // Mock the initial list fetch
        coEvery { apiClient.listVaults() } returns ApiResult.Success(
            listOf(vault1, vault2)
        )

        // Mock WebSocket to emit a vault_updated event for vault-1 with updated balance
        val updatedVault1 = vault1.copy(balance = 15_000_000L)
        coEvery { vaultEventSocket.events("vault-1") } returns flow {
            emit(VaultEvent(vault = updatedVault1))
        }
        coEvery { vaultEventSocket.events("vault-2") } returns flow { }

        val vm = makeVm()

        // Load initial list
        vm.load()

        // Verify initial list was fetched once
        coVerify(exactly = 1) { apiClient.listVaults() }
        assert(vm.state.value.vaults == listOf(vault1, vault2))

        // Now the WebSocket event comes in for vault-1
        // (The event collection happens in subscribeToEvents, triggered by load())
        // Wait for events to be collected
        Thread.sleep(100)

        // Verify the vault was updated in place
        assert(vm.state.value.vaults[0].balance == 15_000_000L)
        assert(vm.state.value.vaults[1].balance == 20_000_000L)

        // CRITICAL: listVaults should NOT have been called again
        // (still exactly 1 call from the initial load)
        coVerify(exactly = 1) { apiClient.listVaults() }
    }

    @Test
    fun `multiple sequential WebSocket events do not trigger list refetch`() = runTest {
        val vault = Vault(
            id = "vault-1", owner = "GABC", beneficiary = "GXYZ",
            balance = 10_000_000L, checkInInterval = 2_592_000L,
            lastCheckIn = "2026-04-01T00:00:00Z", ttlRemaining = 172_800L,
            status = VaultStatus.active
        )

        coEvery { apiClient.listVaults() } returns ApiResult.Success(listOf(vault))

        // Emit multiple balance updates via WebSocket
        var emittedCount = 0
        coEvery { vaultEventSocket.events("vault-1") } returns flow {
            for (balance in listOf(15_000_000L, 20_000_000L, 25_000_000L)) {
                emit(VaultEvent(vault = vault.copy(balance = balance)))
                emittedCount++
            }
        }

        val vm = makeVm()

        vm.load()
        Thread.sleep(100)

        // Verify all WebSocket updates were received and applied
        assert(emittedCount == 3)

        // Verify each update was applied to local state
        val finalBalance = vm.state.value.vaults[0].balance
        assert(finalBalance == 25_000_000L)

        // Verify listVaults was called exactly once (only for initial load, not per WebSocket event)
        coVerify(exactly = 1) { apiClient.listVaults() }
    }

    @Test
    fun `beneficiary update uses targeted refresh instead of full list reload`() = runTest {
        val vault = Vault(
            id = "vault-1", owner = "GABC", beneficiary = "GXYZ",
            balance = 10_000_000L, checkInInterval = 2_592_000L,
            lastCheckIn = "2026-04-01T00:00:00Z", ttlRemaining = 172_800L,
            status = VaultStatus.active
        )

        coEvery { apiClient.listVaults() } returns ApiResult.Success(listOf(vault))
        coEvery { apiClient.updateBeneficiary(any(), any()) } returns ApiResult.Success(Unit)

        val updatedVault = vault.copy(beneficiary = "GNEW")
        coEvery { apiClient.getVault(vault.id) } returns ApiResult.Success(updatedVault)

        coEvery { vaultEventSocket.events(any()) } returns flow { }

        val vm = makeVm()

        vm.load()
        coVerify(exactly = 1) { apiClient.listVaults() }

        // Update beneficiary
        vm.updateBeneficiary("vault-1", "GNEW")
        Thread.sleep(100)

        // Verify getVault (single-vault fetch) was called, not listVaults
        coVerify(exactly = 1) { apiClient.getVault("vault-1") }
        // listVaults should still be called exactly once (only the initial load)
        coVerify(exactly = 1) { apiClient.listVaults() }
    }
}
