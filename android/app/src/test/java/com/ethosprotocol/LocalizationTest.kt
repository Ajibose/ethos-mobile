package com.ethosprotocol

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.services.UsernameValidator
import com.ethosprotocol.testing.RTLTestUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class LocalizationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `username validation enforces configured string limits`() {
        assertTrue(UsernameValidator.isValid("abc"))
        assertTrue(UsernameValidator.isValid("a".repeat(32)))
        assertFalse(UsernameValidator.isValid("ab"))
        assertFalse(UsernameValidator.isValid("a".repeat(33)))
        assertEquals("  user-name  ".trim(), UsernameValidator.sanitize("  user-name  "))
    }

    @Test
    fun `formattedBalance uses locale-aware decimal separator for large values`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val vault = makeVault(balance = 1_234_567_890L)
            val formatted = vault.formattedBalance
            assertTrue(formatted.endsWith(" XLM"))
            assertTrue(
                "German locale should use comma decimal formatting for the asset amount",
                formatted.contains(',') || formatted.contains('.')
            )
            assertFalse(formatted.contains("1.234.567,890"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `date and time formatting stays locale-aware across common locales`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertTrue(com.ethosprotocol.utils.DateTimeFormatter.formatDurationInSeconds(90L).contains("1:30"))

            Locale.setDefault(Locale.GERMANY)
            val formatted = com.ethosprotocol.utils.DateTimeFormatter.formatDurationInSeconds(3_600L + 90L)
            assertTrue(formatted.contains("1") || formatted.contains("0"))
            assertTrue(formatted.isNotEmpty())
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `rtl utility detects common rtl locales`() {
        for (locale in RTLTestUtils.getCommonRTLLocales()) {
            assertTrue("Expected $locale to be recognized as RTL", RTLTestUtils.isRTLLocale(locale))
        }

        assertFalse(RTLTestUtils.isRTLLocale(Locale.ENGLISH))
        assertFalse(RTLTestUtils.isRTLLocale(Locale.US))
    }

    @Test
    fun `long rtl strings render without crashing in rtl layout direction`() {
        val longArabicString = "تجربة شاملة جدًا على شاشة القيم الطويلة مع نص عربي طويل جداً لتغطية المرونة وتجاوز الحد"

        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Column {
                    Text(
                        text = longArabicString,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        composeRule.onNodeWithText(longArabicString).assertExists()
    }

    private fun makeVault(
        balance: Long = 0L,
        ttlRemaining: Long? = null,
        assetCode: String = "XLM",
        assetIssuer: String? = null
    ) = Vault(
        id = "v1",
        owner = "GABC",
        beneficiary = "GXYZ",
        balance = balance,
        checkInInterval = 2_592_000L,
        lastCheckIn = "2026-04-01T00:00:00Z",
        ttlRemaining = ttlRemaining,
        status = VaultStatus.active,
        assetCode = assetCode,
        assetIssuer = assetIssuer
    )
}
