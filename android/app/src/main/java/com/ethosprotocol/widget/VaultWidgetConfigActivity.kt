package com.ethosprotocol.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ethosprotocol.R

/**
 * Widget configuration activity (#245 / #431).
 *
 * Three-step flow:
 *   1. Vault selection   – pin a specific vault to this widget instance (#245 / #246).
 *   2. Refresh interval  – choose how often the widget polls for new data (#431).
 *   3. Colour scheme     – pick an accent colour for the widget (#431).
 *
 * After all three steps the activity commits the selections to per-widget
 * SharedPreferences, triggers an immediate widget update, and returns RESULT_OK.
 * Pressing back at any step cancels and returns RESULT_CANCELED (launcher removes
 * the widget).
 */
class VaultWidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    // Selections accumulated across steps.
    private var selectedVaultId: String? = null
    private var selectedRefreshMinutes: Int = VaultStatusWidget.DEFAULT_REFRESH_INTERVAL
    private var selectedColorScheme: String = VaultStatusWidget.COLOR_SCHEME_AUTO

    // Step index: 0 = vault, 1 = refresh interval, 2 = colour scheme.
    private var step = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Returning RESULT_CANCELED causes the launcher to remove the widget if the
        // activity finishes before setting RESULT_OK.
        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        showStep()
    }

    // -------------------------------------------------------------------------
    // Step routing
    // -------------------------------------------------------------------------

    private fun showStep() {
        when (step) {
            0 -> showVaultStep()
            1 -> showRefreshIntervalStep()
            2 -> showColorSchemeStep()
            else -> finishWithSelections()
        }
    }

    // -------------------------------------------------------------------------
    // Step 0: vault selection
    // -------------------------------------------------------------------------

    private fun showVaultStep() {
        val vaultIds = loadVaultIdList(this)

        if (vaultIds.isEmpty()) {
            Toast.makeText(this, getString(R.string.widget_no_vaults), Toast.LENGTH_SHORT).show()
            // Skip vault pinning (urgency fallback) but still go through the config steps.
            selectedVaultId = null
            step = 1
            showStep()
            return
        }

        val layout = buildListLayout(getString(R.string.widget_configure_title))
        val listView = ListView(this)
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, vaultIds)
        layout.addView(listView)
        setContentView(layout)

        listView.setOnItemClickListener { _, _, position, _ ->
            selectedVaultId = vaultIds[position]
            step = 1
            showStep()
        }
    }

    // -------------------------------------------------------------------------
    // Step 1: refresh interval (#431)
    // -------------------------------------------------------------------------

    private fun showRefreshIntervalStep() {
        val options = listOf(
            getString(R.string.widget_config_refresh_15) to 15,
            getString(R.string.widget_config_refresh_30) to 30,
            getString(R.string.widget_config_refresh_60) to 60
        )

        val layout = buildListLayout(getString(R.string.widget_config_refresh_title))
        val listView = ListView(this)
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            options.map { it.first })
        layout.addView(listView)
        setContentView(layout)

        listView.setOnItemClickListener { _, _, position, _ ->
            selectedRefreshMinutes = options[position].second
            step = 2
            showStep()
        }
    }

    // -------------------------------------------------------------------------
    // Step 2: colour scheme (#431)
    // -------------------------------------------------------------------------

    private fun showColorSchemeStep() {
        val options = listOf(
            getString(R.string.widget_config_color_auto)   to VaultStatusWidget.COLOR_SCHEME_AUTO,
            getString(R.string.widget_config_color_blue)   to VaultStatusWidget.COLOR_SCHEME_BLUE,
            getString(R.string.widget_config_color_green)  to VaultStatusWidget.COLOR_SCHEME_GREEN,
            getString(R.string.widget_config_color_orange) to VaultStatusWidget.COLOR_SCHEME_ORANGE
        )

        val layout = buildListLayout(getString(R.string.widget_config_color_title))
        val listView = ListView(this)
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            options.map { it.first })
        layout.addView(listView)
        setContentView(layout)

        listView.setOnItemClickListener { _, _, position, _ ->
            selectedColorScheme = options[position].second
            step = 3
            showStep()
        }
    }

    // -------------------------------------------------------------------------
    // Finish: persist and update
    // -------------------------------------------------------------------------

    private fun finishWithSelections() {
        // Persist vault selection (null = urgency fallback).
        selectedVaultId?.let { VaultStatusWidget.saveSelectedVaultId(this, appWidgetId, it) }
        // #431: Persist refresh interval and colour scheme.
        VaultStatusWidget.saveRefreshIntervalMinutes(this, appWidgetId, selectedRefreshMinutes)
        VaultStatusWidget.saveColorScheme(this, appWidgetId, selectedColorScheme)

        // Trigger an immediate update so the newly configured vault is visible right away.
        val manager = AppWidgetManager.getInstance(this)
        VaultStatusWidget.updateWidget(this, manager, appWidgetId)

        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(Activity.RESULT_OK, resultValue)
        finish()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildListLayout(title: String): android.widget.LinearLayout {
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            addView(TextView(this@VaultWidgetConfigActivity).apply {
                text = title
                textSize = 18f
                setPadding(0, 0, 0, 24)
            })
        }
    }

    companion object {
        /** Loads the comma-separated vault ID list saved by [VaultWidgetUpdateWorker]. */
        fun loadVaultIdList(context: Context): List<String> {
            val raw = context.getSharedPreferences(
                VaultStatusWidget.PREFS_SHARED, Context.MODE_PRIVATE
            ).getString(VaultStatusWidget.KEY_VAULT_ID_LIST, null)
            return if (raw.isNullOrBlank()) emptyList()
            else raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}
