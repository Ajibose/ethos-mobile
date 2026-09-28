package com.ethosprotocol

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ethosprotocol.widget.VaultStatusWidget
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for widget configuration persistence and multi-vault data helpers (#431 / #433).
 *
 * Uses Robolectric so SharedPreferences is fully functional without a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class VaultWidgetConfigTest {

    private lateinit var context: Context
    private val widgetId = 42

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // -------------------------------------------------------------------------
    // #431 — refresh interval persistence
    // -------------------------------------------------------------------------

    @Test
    fun `getRefreshIntervalMinutes defaults to 15 when not set`() {
        assertEquals(15, VaultStatusWidget.getRefreshIntervalMinutes(context, widgetId))
    }

    @Test
    fun `saveRefreshIntervalMinutes persists value and getRefreshIntervalMinutes returns it`() {
        VaultStatusWidget.saveRefreshIntervalMinutes(context, widgetId, 30)
        assertEquals(30, VaultStatusWidget.getRefreshIntervalMinutes(context, widgetId))
    }

    @Test
    fun `saveRefreshIntervalMinutes accepts 60 minutes`() {
        VaultStatusWidget.saveRefreshIntervalMinutes(context, widgetId, 60)
        assertEquals(60, VaultStatusWidget.getRefreshIntervalMinutes(context, widgetId))
    }

    @Test
    fun `different widget ids have independent refresh interval prefs`() {
        VaultStatusWidget.saveRefreshIntervalMinutes(context, widgetId, 15)
        VaultStatusWidget.saveRefreshIntervalMinutes(context, widgetId + 1, 60)
        assertEquals(15, VaultStatusWidget.getRefreshIntervalMinutes(context, widgetId))
        assertEquals(60, VaultStatusWidget.getRefreshIntervalMinutes(context, widgetId + 1))
    }

    // -------------------------------------------------------------------------
    // #431 — color scheme persistence
    // -------------------------------------------------------------------------

    @Test
    fun `getColorScheme defaults to auto when not set`() {
        assertEquals(VaultStatusWidget.COLOR_SCHEME_AUTO, VaultStatusWidget.getColorScheme(context, widgetId + 10))
    }

    @Test
    fun `saveColorScheme persists value and getColorScheme returns it`() {
        VaultStatusWidget.saveColorScheme(context, widgetId, VaultStatusWidget.COLOR_SCHEME_BLUE)
        assertEquals(VaultStatusWidget.COLOR_SCHEME_BLUE, VaultStatusWidget.getColorScheme(context, widgetId))
    }

    @Test
    fun `saveColorScheme can store orange scheme`() {
        VaultStatusWidget.saveColorScheme(context, widgetId, VaultStatusWidget.COLOR_SCHEME_ORANGE)
        assertEquals(VaultStatusWidget.COLOR_SCHEME_ORANGE, VaultStatusWidget.getColorScheme(context, widgetId))
    }

    @Test
    fun `different widget ids have independent color scheme prefs`() {
        VaultStatusWidget.saveColorScheme(context, widgetId, VaultStatusWidget.COLOR_SCHEME_BLUE)
        VaultStatusWidget.saveColorScheme(context, widgetId + 1, VaultStatusWidget.COLOR_SCHEME_GREEN)
        assertEquals(VaultStatusWidget.COLOR_SCHEME_BLUE, VaultStatusWidget.getColorScheme(context, widgetId))
        assertEquals(VaultStatusWidget.COLOR_SCHEME_GREEN, VaultStatusWidget.getColorScheme(context, widgetId + 1))
    }

    // -------------------------------------------------------------------------
    // #433 — additional vault rows persistence
    // -------------------------------------------------------------------------

    @Test
    fun `getAdditionalVaults returns empty list when not set`() {
        assertTrue(VaultStatusWidget.getAdditionalVaults(context, widgetId + 20).isEmpty())
    }

    @Test
    fun `saveAdditionalVaults persists one row and getAdditionalVaults returns it`() {
        val rows = listOf(Triple("vault-2", "vault-2…", "1d 2h"))
        VaultStatusWidget.saveAdditionalVaults(context, widgetId, rows)

        val result = VaultStatusWidget.getAdditionalVaults(context, widgetId)
        assertEquals(1, result.size)
        assertEquals("vault-2", result[0].first)
        assertEquals("vault-2…", result[0].second)
        assertEquals("1d 2h", result[0].third)
    }

    @Test
    fun `saveAdditionalVaults persists two rows`() {
        val rows = listOf(
            Triple("v2", "v2…", "2h"),
            Triple("v3", "v3…", "30m")
        )
        VaultStatusWidget.saveAdditionalVaults(context, widgetId, rows)

        val result = VaultStatusWidget.getAdditionalVaults(context, widgetId)
        assertEquals(2, result.size)
        assertEquals("v2", result[0].first)
        assertEquals("v3", result[1].first)
    }

    @Test
    fun `saveAdditionalVaults caps at two rows even when three provided`() {
        val rows = listOf(
            Triple("v2", "v2…", "3h"),
            Triple("v3", "v3…", "2h"),
            Triple("v4", "v4…", "1h")
        )
        VaultStatusWidget.saveAdditionalVaults(context, widgetId, rows)

        val result = VaultStatusWidget.getAdditionalVaults(context, widgetId)
        assertEquals(2, result.size)
        assertEquals("v2", result[0].first)
        assertEquals("v3", result[1].first)
    }

    @Test
    fun `saveAdditionalVaults with empty list clears previous rows`() {
        val rows = listOf(Triple("v2", "v2…", "1h"))
        VaultStatusWidget.saveAdditionalVaults(context, widgetId, rows)
        VaultStatusWidget.saveAdditionalVaults(context, widgetId, emptyList())

        assertTrue(VaultStatusWidget.getAdditionalVaults(context, widgetId).isEmpty())
    }

    // -------------------------------------------------------------------------
    // #433 — selectLayout with multi-vault flag
    // -------------------------------------------------------------------------

    @Test
    fun `selectLayout returns large_multi when wide and hasAdditionalVaults`() {
        val opts = android.os.Bundle().apply {
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260)
        }
        val layout = VaultStatusWidget.selectLayout(opts, hasAdditionalVaults = true)
        assertEquals(com.ethosprotocol.R.layout.vault_widget_large_multi, layout)
    }

    @Test
    fun `selectLayout returns large when wide and no additional vaults`() {
        val opts = android.os.Bundle().apply {
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 260)
        }
        val layout = VaultStatusWidget.selectLayout(opts, hasAdditionalVaults = false)
        assertEquals(com.ethosprotocol.R.layout.vault_widget_large, layout)
    }

    @Test
    fun `selectLayout returns medium for medium width regardless of additional vaults`() {
        val opts = android.os.Bundle().apply {
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 200)
        }
        val layout = VaultStatusWidget.selectLayout(opts, hasAdditionalVaults = true)
        assertEquals(com.ethosprotocol.R.layout.vault_widget_medium, layout)
    }

    @Test
    fun `selectLayout returns small for narrow width`() {
        val opts = android.os.Bundle().apply {
            putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 100)
        }
        val layout = VaultStatusWidget.selectLayout(opts, hasAdditionalVaults = true)
        assertEquals(com.ethosprotocol.R.layout.vault_widget_small, layout)
    }

    // -------------------------------------------------------------------------
    // VaultWidgetConfigActivity.loadVaultIdList
    // -------------------------------------------------------------------------

    @Test
    fun `loadVaultIdList returns empty list when prefs not populated`() {
        val ids = VaultWidgetConfigActivity.loadVaultIdList(context)
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `loadVaultIdList parses comma-separated vault IDs`() {
        context.getSharedPreferences(VaultStatusWidget.PREFS_SHARED, Context.MODE_PRIVATE)
            .edit().putString(VaultStatusWidget.KEY_VAULT_ID_LIST, "vault-1,vault-2,vault-3").apply()

        val ids = VaultWidgetConfigActivity.loadVaultIdList(context)
        assertEquals(listOf("vault-1", "vault-2", "vault-3"), ids)
    }

    @Test
    fun `loadVaultIdList trims whitespace from entries`() {
        context.getSharedPreferences(VaultStatusWidget.PREFS_SHARED, Context.MODE_PRIVATE)
            .edit().putString(VaultStatusWidget.KEY_VAULT_ID_LIST, " vault-a , vault-b ").apply()

        val ids = VaultWidgetConfigActivity.loadVaultIdList(context)
        assertEquals(listOf("vault-a", "vault-b"), ids)
    }
}
