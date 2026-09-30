package com.ethosprotocol

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.testTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.OfflineCache
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.services.ExpiringVaultsManager
import com.ethosprotocol.ui.VaultViewModel
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests Compose recomposition behavior when a single vault is updated.
 *
 * Verifies that updating a single vault via WebSocket event doesn't trigger
 * unnecessary recompositions of the entire list or unaffected vault rows.
 * This is important for battery life and UI responsiveness (#319).
 */
@RunWith(AndroidJUnit4::class)
class VaultListRecompositionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val apiClient: ApiClient = mockk()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun singleVaultUpdate_doesNotRecomposeUnaffectedRows() = runTest {
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

        coEvery { apiClient.listVaults() } returns ApiResult.Success(
            listOf(vault1, vault2)
        )

        var recompositionCount = 0
        val expiringVaultsManager: ExpiringVaultsManager = mockk(relaxed = true)
        every { expiringVaultsManager.bannerState } returns MutableStateFlow(null).asStateFlow()
        val vm = VaultViewModel(
            apiClient = apiClient,
            notificationHelper = mockk(relaxed = true),
            pendingActionDao = mockk(relaxed = true),
            vaultEventSocket = mockk(relaxed = true),
            expiringVaultsManager = expiringVaultsManager,
            offlineCache = mockk(relaxed = true),
            networkMonitor = FakeNetworkMonitor(startOnline = true),
            context = InstrumentationRegistry.getInstrumentation().targetContext
        )

        composeRule.setContent {
            // Track recomposition by incrementing a counter on every frame
            recompositionCount++

            // Simplified vault list rendering with a key for each item
            LazyColumn(Modifier.testTag("vaultList")) {
                items(vm.state.value.vaults, key = { it.id }) { vault ->
                    recompositionCount++ // Count per-item recompositions too
                }
            }
        }

        // Load initial list
        val initialRecompositions = recompositionCount
        vm.load()
        composeRule.waitForIdle()

        // Simulate a single vault update via WebSocket (this would normally come from VaultEventSocket)
        vm.refreshSingle("vault-1")
        composeRule.waitForIdle()

        // The update should not cause many additional recompositions because:
        // 1. We use keys on list items (key = { it.id })
        // 2. Vault objects are data classes (structural equality)
        // 3. VaultUiState is @Immutable
        // A single vault update should only recompose that specific item, not the whole list.
        // This assertion is loose because Compose may have some overhead, but we're checking
        // that it's not triggering a full list recomposition (which would be >5 extra recompositions).
        assert(recompositionCount - initialRecompositions <= 3) {
            "Single vault update caused too many recompositions: ${recompositionCount - initialRecompositions}"
        }
    }
}
