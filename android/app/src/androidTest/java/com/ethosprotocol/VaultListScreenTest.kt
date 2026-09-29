package com.ethosprotocol

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.ui.screens.VaultListScreen
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class VaultListScreenTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createComposeRule()

    @Before fun setup() { hiltRule.inject() }

    @Test
    fun emptyState_showsCreatePrompt() {
        composeRule.setContent { VaultListScreen(onVaultClick = {}) }
        composeRule.onNodeWithText("No vaults yet", substring = true).assertIsDisplayed()
    }

    @Test
    fun addButton_isDisplayed() {
        composeRule.setContent { VaultListScreen(onVaultClick = {}) }
        composeRule.onNodeWithContentDescription("Create vault").assertIsDisplayed()
    }

    // ── #214 Last-remaining-passkey sign-out warning ─────────────────────────

    @Test
    fun signOut_whenLastRemainingPasskey_showsBlockingWarning_insteadOfSigningOutImmediately() {
        var signedOut = false
        composeRule.setContent {
            VaultListScreen(
                onVaultClick = {},
                onSignOut = { signedOut = true },
                checkLastRemainingPasskey = { true }
            )
        }

        composeRule.onNodeWithContentDescription("Sign out").performClick()

        composeRule.onNodeWithText("This Is Your Only Passkey").assertIsDisplayed()
        assert(!signedOut) { "Sign-out must be blocked behind the confirmation, not fired immediately" }
    }

    @Test
    fun signOut_whenLastRemainingPasskey_confirmingWarning_signsOut() {
        var signedOut = false
        composeRule.setContent {
            VaultListScreen(
                onVaultClick = {},
                onSignOut = { signedOut = true },
                checkLastRemainingPasskey = { true }
            )
        }

        composeRule.onNodeWithContentDescription("Sign out").performClick()
        composeRule.onNodeWithText("Sign Out Anyway").performClick()

        assert(signedOut) { "Confirming the warning must proceed with sign-out" }
    }

    // ── #215 Create-vault confirmation step ─────────────────────────────────

    private val validBeneficiary = "GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF"

    @Test
    fun createVault_next_showsConfirmationBeforeCreating() {
        composeRule.setContent { VaultListScreen(onVaultClick = {}) }

        composeRule.onNodeWithContentDescription("Create vault").performClick()
        composeRule.onNodeWithText("Beneficiary Stellar address").performTextInput(validBeneficiary)
        composeRule.onNodeWithText("Next").performClick()

        composeRule.onNodeWithText("Confirm Vault").assertIsDisplayed()
        composeRule.onNodeWithText("Confirm & Create").assertIsDisplayed()
    }

    @Test
    fun createVault_back_returnsToInputFormWithoutCreating() {
        composeRule.setContent { VaultListScreen(onVaultClick = {}) }

        composeRule.onNodeWithContentDescription("Create vault").performClick()
        composeRule.onNodeWithText("Beneficiary Stellar address").performTextInput(validBeneficiary)
        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("Back").performClick()

        composeRule.onNodeWithText("New Vault").assertIsDisplayed()
        composeRule.onNodeWithText("Confirm & Create").assertDoesNotExist()
    }

    @Test
    fun signOut_whenNotLastRemainingPasskey_signsOutImmediately_withNoWarning() {
        var signedOut = false
        composeRule.setContent {
            VaultListScreen(
                onVaultClick = {},
                onSignOut = { signedOut = true },
                checkLastRemainingPasskey = { false }
            )
        }

        composeRule.onNodeWithContentDescription("Sign out").performClick()

        assert(signedOut) { "Sign-out should proceed immediately when it isn't the last passkey" }
        composeRule.onNodeWithText("This Is Your Only Passkey").assertDoesNotExist()
    }

    // ── #446 Integration tests against the real API ─────────────────────────
    //
    // These tests exercise the full vault-list flow end-to-end against a live
    // API instead of mocks. They are opt-in: the suite is skipped unless the
    // test API environment is configured, so the default (mock) CI job stays
    // hermetic. The dedicated integration CI job sets these values.
    //
    // Required environment (see README "Integration test API requirements"):
    //   ETHOS_API_BASE_URL  – base URL of the test API, e.g. https://api.test.ethosprotocol.com
    //   ETHOS_API_TOKEN     – bearer token for the test account

    private val apiBaseUrl: String? = System.getenv("ETHOS_API_BASE_URL")
    private val apiToken: String? = System.getenv("ETHOS_API_TOKEN")

    private fun requireTestApi() {
        assumeTrue(
            "Integration tests require ETHOS_API_BASE_URL and ETHOS_API_TOKEN",
            !apiBaseUrl.isNullOrBlank() && !apiToken.isNullOrBlank()
        )
    }

    @Test
    fun integration_vaultList_fetchesFromRealApi_endToEnd() {
        requireTestApi()

        val client = RealApiTestClient(baseUrl = apiBaseUrl!!, token = apiToken!!)
        val vaults: List<Vault> = client.fetchVaults()

        // The real API must return a well-formed payload the UI can render.
        vaults.forEach { vault ->
            assert(vault.id.isNotBlank()) { "API returned a vault without an id" }
            assert(vault.status in VaultStatus.values().toList()) {
                "API returned an unknown vault status: ${vault.status}"
            }
        }

        composeRule.setContent { VaultListScreen(onVaultClick = {}) }
        if (vaults.isEmpty()) {
            composeRule.onNodeWithText("No vaults yet", substring = true).assertIsDisplayed()
        } else {
            composeRule.onNodeWithText(vaults.first().name, substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun integration_vaultList_roundTrip_createThenFetch() {
        requireTestApi()

        val client = RealApiTestClient(baseUrl = apiBaseUrl!!, token = apiToken!!)
        val created = client.createVault(beneficiary = validBeneficiary)
        val fetched = client.fetchVaults()

        assert(fetched.any { it.id == created.id }) {
            "Vault created via the real API must appear in the subsequent list fetch"
        }
    }
}
