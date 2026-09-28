package com.ethosprotocol

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.ApiResult
import com.ethosprotocol.api.OfflineCache
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.models.*
import com.ethosprotocol.services.ExpiringVaultsManager
import com.ethosprotocol.services.PasskeyService
import com.ethosprotocol.ui.AcceptanceViewModel
import com.ethosprotocol.ui.AuthViewModel
import com.ethosprotocol.ui.TwoFactorViewModel
import com.ethosprotocol.ui.VaultViewModel
import com.ethosprotocol.ui.screens.*
import com.ethosprotocol.services.NotificationDeliveryLog
import com.ethosprotocol.services.NotificationHelper
import com.ethosprotocol.services.PendingActionDao
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ethosprotocol.services.PendingActionSyncWorker
import com.ethosprotocol.services.VaultEventSocket

// ============================================================================
// AuthScreenTest
// ============================================================================

/**
 * Instrumented Compose tests for [AuthScreen].
 *
 * Uses [createComposeRule] with a manually constructed [AuthViewModel] so the screen
 * can be exercised in isolation without a running Hilt component.
 */
@HiltAndroidTest
class AuthScreenTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createComposeRule()

    private val apiClient: ApiClient = mockk(relaxed = true)
    private val passkeyService: PasskeyService = mockk(relaxed = true)
    private val tokenProvider: TokenProvider = mockk(relaxed = true)
    private val notificationHelper: NotificationHelper = mockk(relaxed = true)
    private val pendingActionDao: PendingActionDao = mockk(relaxed = true)
    private lateinit var vm: AuthViewModel

    @Before
    fun setUp() {
        hiltRule.inject()
        every { tokenProvider.token } returns null
        vm = AuthViewModel(apiClient, passkeyService, tokenProvider, notificationHelper, pendingActionDao)
    }

    @Test
    fun authScreen_showsSignInButton() {
        composeRule.setContent { AuthScreen(vm = vm) }

        composeRule.onNodeWithText("Sign in with Passkey").assertIsDisplayed()
    }

    @Test
    fun authScreen_showsCreateAccountButton() {
        composeRule.setContent { AuthScreen(vm = vm) }

        composeRule.onNodeWithText("Create account").assertIsDisplayed()
    }

    @Test
    fun authScreen_showsAppTitle() {
        composeRule.setContent { AuthScreen(vm = vm) }

        composeRule.onNodeWithText("Ethos-Protocol").assertIsDisplayed()
    }

    @Test
    fun authScreen_tapCreateAccount_showsRegisterDialog() {
        composeRule.setContent { AuthScreen(vm = vm) }

        composeRule.onNodeWithText("Create account").performClick()

        // The register dialog should appear with username field
        composeRule.onNodeWithText("Create Account").assertIsDisplayed()
        composeRule.onNodeWithText("Username").assertIsDisplayed()
    }

    @Test
    fun authScreen_registerDialog_registerButtonDisabledWhenUsernameBlank() {
        composeRule.setContent { AuthScreen(vm = vm) }

        composeRule.onNodeWithText("Create account").performClick()
        // Register button should be disabled with no username entered
        composeRule.onNodeWithText("Register").assertIsNotEnabled()
    }

    @Test
    fun authScreen_registerDialog_registerButtonEnabledAfterUsernameEntered() {
        composeRule.setContent { AuthScreen(vm = vm) }

        composeRule.onNodeWithText("Create account").performClick()
        composeRule.onNodeWithText("Username").performTextInput("alice")

        composeRule.onNodeWithText("Register").assertIsEnabled()
    }

    @Test
    fun authScreen_registerDialog_cancelDismissesDialog() {
        composeRule.setContent { AuthScreen(vm = vm) }

        composeRule.onNodeWithText("Create account").performClick()
        composeRule.onNodeWithText("Create Account").assertIsDisplayed()

        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithText("Sign in with Passkey").assertIsDisplayed()
    }

    /**
     * Stub error message test for #66: a sign-in failure from the passkey service
     * must surface a human-readable error string in the UI.  The current implementation
     * shows [Exception.message] directly.  Once #66 is implemented with a proper user-
     * facing copy, this test should be updated to assert the exact string — removing this
     * comment is the deliberate, visible test change.
     */
    @Test
    fun authScreen_signInError_isDisplayed() {
        coEvery { passkeyService.authenticate(any()) } returns
            Result.failure(RuntimeException("Passkey error #66"))

        composeRule.setContent { AuthScreen(vm = vm) }

        // Trigger sign-in (no real Activity so passkey will fail)
        composeRule.onNodeWithText("Sign in with Passkey").performClick()

        // The error text should appear somewhere in the UI
        composeRule.onNodeWithText("Passkey error #66", substring = true).assertIsDisplayed()
    }
}

// ============================================================================
// BeneficiaryAcceptanceScreenTest
// ============================================================================

@HiltAndroidTest
class BeneficiaryAcceptanceScreenTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createComposeRule()

    private val apiClient: ApiClient = mockk()
    private lateinit var vm: AcceptanceViewModel

    @Before
    fun setUp() {
        hiltRule.inject()
        vm = AcceptanceViewModel(apiClient)
    }

    @Test
    fun acceptanceScreen_showsVaultId() {
        composeRule.setContent {
            BeneficiaryAcceptanceScreen(
                vaultId = "VAULT-ABC-123",
                token = "test-token",
                onAccepted = {},
                onDecline = {},
                vm = vm
            )
        }

        composeRule.onNodeWithText("VAULT-ABC-123").assertIsDisplayed()
    }

    @Test
    fun acceptanceScreen_showsAcceptAndDeclineButtons() {
        composeRule.setContent {
            BeneficiaryAcceptanceScreen(
                vaultId = "v1",
                token = "test-token",
                onAccepted = {},
                onDecline = {},
                vm = vm
            )
        }

        composeRule.onNodeWithText("Accept").assertIsDisplayed()
        composeRule.onNodeWithText("Decline").assertIsDisplayed()
    }

    @Test
    fun acceptanceScreen_declineButton_callsOnDeclineCallback() {
        var declined = false
        composeRule.setContent {
            BeneficiaryAcceptanceScreen(
                vaultId = "v1",
                token = "test-token",
                onAccepted = {},
                onDecline = { declined = true },
                vm = vm
            )
        }

        composeRule.onNodeWithText("Decline").performClick()

        assert(declined) { "onDecline callback was not invoked" }
    }

    /**
     * Stub error for #67: network-unavailable during acceptance must show a user-facing
     * error message.  The exact copy ("No network. Please try again.") is what the
     * ViewModel currently emits.  Removing/changing this string after #67 is resolved
     * is a deliberate, visible test change.
     */
    @Test
    fun acceptanceScreen_networkError_isDisplayed() {
        coEvery { apiClient.acceptBeneficiary(any(), any()) } returns ApiResult.NetworkUnavailable

        composeRule.setContent {
            BeneficiaryAcceptanceScreen(
                vaultId = "v1",
                token = "test-token",
                onAccepted = {},
                onDecline = {},
                vm = vm
            )
        }

        composeRule.onNodeWithText("Accept").performClick()

        composeRule.onNodeWithText("No network. Please try again.", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun acceptanceScreen_apiError_isDisplayed() {
        coEvery { apiClient.acceptBeneficiary(any(), any()) } returns
            ApiResult.Error("Vault not found", 404)

        composeRule.setContent {
            BeneficiaryAcceptanceScreen(
                vaultId = "v1",
                token = "test-token",
                onAccepted = {},
                onDecline = {},
                vm = vm
            )
        }

        composeRule.onNodeWithText("Accept").performClick()

        composeRule.onNodeWithText("Vault not found", substring = true).assertIsDisplayed()
    }

    @Test
    fun acceptanceScreen_acceptSuccess_invokesOnAcceptedCallback() {
        var accepted = false
        coEvery { apiClient.acceptBeneficiary("v1", "test-token") } returns ApiResult.Success(Unit)

        composeRule.setContent {
            BeneficiaryAcceptanceScreen(
                vaultId = "v1",
                token = "test-token",
                onAccepted = { accepted = true },
                onDecline = {},
                vm = vm
            )
        }

        composeRule.onNodeWithText("Accept").performClick()
        composeRule.waitForIdle()

        assert(accepted) { "onAccepted callback was not invoked" }
    }
}

// ============================================================================
// TwoFactorSetupScreenTest
// ============================================================================

@HiltAndroidTest
class TwoFactorSetupScreenTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createComposeRule()

    private val apiClient: ApiClient = mockk(relaxed = true)
    private lateinit var vm: TwoFactorViewModel

    @Before
    fun setUp() {
        hiltRule.inject()
        vm = TwoFactorViewModel(apiClient)
    }

    @Test
    fun twoFactorSetupScreen_showsMethodOptions() {
        composeRule.setContent {
            TwoFactorSetupScreen(vaultId = "v1", onComplete = {}, onDismiss = {})
        }

        composeRule.onNodeWithText("Authenticator App (TOTP)").assertIsDisplayed()
        composeRule.onNodeWithText("SMS Code").assertIsDisplayed()
        composeRule.onNodeWithText("Email Code").assertIsDisplayed()
    }

    @Test
    fun twoFactorSetupScreen_showsContinueAndCancelButtons() {
        composeRule.setContent {
            TwoFactorSetupScreen(vaultId = "v1", onComplete = {}, onDismiss = {})
        }

        composeRule.onNodeWithText("Continue").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun twoFactorSetupScreen_selectSms_showsPhoneField() {
        composeRule.setContent {
            TwoFactorSetupScreen(vaultId = "v1", onComplete = {}, onDismiss = {})
        }

        composeRule.onNodeWithText("SMS Code").performClick()

        composeRule.onNodeWithText("Phone number").assertIsDisplayed()
    }

    @Test
    fun twoFactorSetupScreen_selectEmail_showsEmailField() {
        composeRule.setContent {
            TwoFactorSetupScreen(vaultId = "v1", onComplete = {}, onDismiss = {})
        }

        composeRule.onNodeWithText("Email Code").performClick()

        composeRule.onNodeWithText("Email address").assertIsDisplayed()
    }

    @Test
    fun twoFactorSetupScreen_smsContinueDisabledWithoutPhone() {
        composeRule.setContent {
            TwoFactorSetupScreen(vaultId = "v1", onComplete = {}, onDismiss = {})
        }

        composeRule.onNodeWithText("SMS Code").performClick()
        // Phone field is blank → Continue must be disabled
        composeRule.onNodeWithText("Continue").assertIsNotEnabled()
    }

    @Test
    fun twoFactorSetupScreen_smsContinueEnabledAfterPhoneEntered() {
        composeRule.setContent {
            TwoFactorSetupScreen(vaultId = "v1", onComplete = {}, onDismiss = {})
        }

        composeRule.onNodeWithText("SMS Code").performClick()
        composeRule.onNodeWithText("Phone number").performTextInput("+15551234567")

        composeRule.onNodeWithText("Continue").assertIsEnabled()
    }

    @Test
    fun twoFactorSetupScreen_cancelInvokesOnDismiss() {
        var dismissed = false
        composeRule.setContent {
            TwoFactorSetupScreen(vaultId = "v1", onComplete = {}, onDismiss = { dismissed = true })
        }

        composeRule.onNodeWithText("Cancel").performClick()

        assert(dismissed) { "onDismiss callback was not invoked" }
    }

    /**
     * Stub error test for #66/#67: a server error during enable2FA must surface
     * an error string in the setup dialog.  Update the exact copy once the final
     * error strings are decided.
     */
    @Test
    fun twoFactorSetupScreen_enable2FAError_isDisplayed() {
        coEvery { apiClient.enable2FA(any(), any()) } returns
            ApiResult.Error("Server error #66", 500)

        composeRule.setContent {
            TwoFactorSetupScreen(vaultId = "v1", onComplete = {}, onDismiss = {})
        }

        // TOTP is selected by default so Continue is enabled
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Server error #66", substring = true).assertIsDisplayed()
    }
}

// ============================================================================
// VaultDeepLinkScreenTest
// ============================================================================

@HiltAndroidTest
class VaultDeepLinkScreenTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createComposeRule()

    private val apiClient: ApiClient = mockk()
    private val notificationHelper: NotificationHelper = mockk(relaxed = true)
    private val pendingActionDao: PendingActionDao = mockk(relaxed = true)
    private val vaultEventSocket: VaultEventSocket = mockk(relaxed = true)
    private val expiringVaultsManager: ExpiringVaultsManager = mockk(relaxed = true)
    private val offlineCache: OfflineCache = mockk(relaxed = true)
    private val fakeNetworkMonitor = FakeNetworkMonitor(startOnline = true)
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var vm: VaultViewModel

    @Before
    fun setUp() {
        hiltRule.inject()
        mockkObject(PendingActionSyncWorker.Companion)
        every { PendingActionSyncWorker.schedule(any()) } just Runs
        every { expiringVaultsManager.bannerState } returns kotlinx.coroutines.flow.MutableStateFlow(null).asStateFlow()
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

    private fun makeVault(id: String) = Vault(
        id = id, owner = "GABC", beneficiary = "GXYZ",
        balance = 10_000_000L, checkInInterval = 2_592_000L,
        lastCheckIn = "2026-04-01T00:00:00Z", ttlRemaining = 172_800L,
        status = VaultStatus.active
    )

    @Test
    fun deepLinkScreen_checkInAction_showsCheckInTitle() {
        coEvery { apiClient.listVaults() } returns ApiResult.Success(listOf(makeVault("v1")))

        composeRule.setContent {
            VaultDeepLinkScreen(
                vaultId = "v1",
                actionPath = "checkin",
                onDone = {},
                vm = vm
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Check In").assertIsDisplayed()
    }

    @Test
    fun deepLinkScreen_withdrawAction_showsWithdrawNotAvailableError() {
        coEvery { apiClient.listVaults() } returns ApiResult.Success(listOf(makeVault("v1")))

        composeRule.setContent {
            VaultDeepLinkScreen(
                vaultId = "v1",
                actionPath = "withdraw",
                onDone = {},
                vm = vm
            )
        }

        composeRule.onNodeWithText("Withdraw").performClick()
        composeRule.waitForIdle()

        /**
         * Stub error for #66: this asserts the current "not yet available" message.
         * Once withdrawal is implemented, remove this assertion and replace with the
         * real flow test — that removal is the deliberate, visible test change.
         */
        composeRule.onNodeWithText("not yet available", substring = true).assertIsDisplayed()
    }

    @Test
    fun deepLinkScreen_manageBeneficiaryAction_showsNotAvailableError() {
        coEvery { apiClient.listVaults() } returns ApiResult.Success(listOf(makeVault("v1")))

        composeRule.setContent {
            VaultDeepLinkScreen(
                vaultId = "v1",
                actionPath = "manage_beneficiary",
                onDone = {},
                vm = vm
            )
        }

        composeRule.onNodeWithText("Manage Beneficiary").performClick()
        composeRule.waitForIdle()

        /**
         * Stub error for #67: same deliberate-change contract as the withdraw test above.
         */
        composeRule.onNodeWithText("not yet available", substring = true).assertIsDisplayed()
    }

    @Test
    fun deepLinkScreen_unknownAction_showsDoneButton() {
        coEvery { apiClient.listVaults() } returns ApiResult.Success(emptyList())

        composeRule.setContent {
            VaultDeepLinkScreen(
                vaultId = "v1",
                actionPath = "unknown_action",
                onDone = {},
                vm = vm
            )
        }

        composeRule.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun deepLinkScreen_doneButton_invokesCallback() {
        coEvery { apiClient.listVaults() } returns ApiResult.Success(emptyList())
        var done = false

        composeRule.setContent {
            VaultDeepLinkScreen(
                vaultId = "v1",
                actionPath = "checkin",
                onDone = { done = true },
                vm = vm
            )
        }

        composeRule.onAllNodesWithText("Done").onFirst().performClick()
        composeRule.waitForIdle()

        assert(done) { "onDone callback was not invoked" }
    }

    @Test
    fun deepLinkScreen_viewDetailsWithVault_showsVaultCard() {
        coEvery { apiClient.listVaults() } returns ApiResult.Success(listOf(makeVault("v1")))

        composeRule.setContent {
            VaultDeepLinkScreen(
                vaultId = "v1",
                actionPath = "view_details",
                onDone = {},
                vm = vm
            )
        }
        composeRule.waitForIdle()

        // VaultCard renders the truncated vault id
        composeRule.onNodeWithText("v1", substring = true).assertIsDisplayed()
    }
}
