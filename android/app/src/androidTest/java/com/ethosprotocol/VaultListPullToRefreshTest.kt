package com.ethosprotocol

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.OfflineCache
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.services.ExpiringVaultsManager
import com.ethosprotocol.ui.VaultViewModel
import com.ethosprotocol.ui.screens.VaultListScreen
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * VaultViewModel is constructed directly (bypassing Hilt) with a mocked ApiClient so the pull
 * gesture can be asserted against a real, non-empty vault list without hitting the network.
 */
@RunWith(AndroidJUnit4::class)
class VaultListPullToRefreshTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val vault = Vault(
        id = "vault-pull-1", owner = "GABC", beneficiary = "GXYZ",
        balance = 10_000_000L, checkInInterval = 2_592_000L,
        lastCheckIn = "2026-04-01T00:00:00Z", ttlRemaining = 172_800L,
        status = VaultStatus.active
    )

    @Test
    fun pullGesture_triggersVaultViewModelLoad() {
        val apiClient: ApiClient = mockk()
        coEvery { apiClient.listVaults() } returns ApiResult.Success(listOf(vault))

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

        composeRule.setContent { VaultListScreen(onVaultClick = {}, vm = vm) }
        composeRule.waitForIdle()
        coVerify(exactly = 1) { apiClient.listVaults() }

        composeRule.onNodeWithTag("vaultListPullToRefresh").performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        coVerify(exactly = 2) { apiClient.listVaults() }
    }
}
