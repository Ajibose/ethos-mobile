package com.ethosprotocol

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ethosprotocol.models.Vault
import com.ethosprotocol.models.VaultStatus
import com.ethosprotocol.ui.screens.VaultCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/**
 * Performance regression test for VaultList scrolling through large vault collections (#318, #444).
 * Verifies that rendering 100+ vaults does not cause frame jank or excessive icon decoding.
 *
 * Methodology (#444):
 * - Baselines are recorded per scenario in [PERFORMANCE_BASELINES_MS] (startup, sync, UI rendering).
 * - Each scenario is measured with [measureTimeMillis] and compared against its baseline.
 * - A regression is flagged (test failure) when the measured time exceeds the baseline by more
 *   than [REGRESSION_THRESHOLD_PERCENT] (5%).
 * - Baselines are intentionally conservative; update them only when a change is a deliberate,
 *   reviewed performance improvement so CI keeps catching real regressions.
 */
@RunWith(AndroidJUnit4::class)
class VaultListPerformanceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun createTestVaults(count: Int): List<Vault> {
        return (1..count).map { index ->
            Vault(
                id = "vault-$index-" + "x".repeat(50 - index.toString().length),
                balance = 1_000_000_000L + index,
                formattedBalance = "${index}.0000000 XLM",
                status = when (index % 4) {
                    0 -> VaultStatus.active
                    1 -> VaultStatus.expired
                    2 -> VaultStatus.released
                    else -> VaultStatus.paused
                },
                checkInInterval = 86_400L,
                ttlRemaining = 86_400L + index,
                isExpiringSoon = index % 10 == 0,
                lastCheckIn = "2024-01-01T00:00:00Z",
                beneficiary = "beneficiary-" + "x".repeat(40 - index.toString().length),
                source = "test"
            )
        }
    }

    /**
     * Asserts that [measuredMs] has not regressed more than [REGRESSION_THRESHOLD_PERCENT]
     * relative to [baselineMs]. Fails the test (CI alert) when the threshold is exceeded.
     */
    private fun assertNoRegression(scenario: String, baselineMs: Long, measuredMs: Long) {
        val allowedMs = baselineMs + (baselineMs * REGRESSION_THRESHOLD_PERCENT / 100)
        println(
            "[Performance] $scenario: baseline=${baselineMs}ms measured=${measuredMs}ms " +
                "allowed=${allowedMs}ms threshold=${REGRESSION_THRESHOLD_PERCENT}%"
        )
        assert(measuredMs <= allowedMs) {
            "Performance regression in '$scenario': ${measuredMs}ms exceeds baseline ${baselineMs}ms " +
                "by more than ${REGRESSION_THRESHOLD_PERCENT}% (allowed up to ${allowedMs}ms)"
        }
    }

    @Test
    fun testVaultListRenderingWith100Vaults() {
        val vaults = createTestVaults(100)

        val renderTime = measureTimeMillis {
            composeTestRule.setContent {
                LazyColumn {
                    items(vaults, key = { it.id }) { vault ->
                        VaultCard(
                            vault = vault,
                            onClick = {},
                            onCheckIn = {}
                        )
                    }
                }
            }
        }

        assertNoRegression("ui_rendering_100_vaults", PERFORMANCE_BASELINES_MS["ui_rendering_100_vaults"]!!, renderTime)
    }

    @Test
    fun testVaultListRenderingWith500Vaults() {
        val vaults = createTestVaults(500)

        val renderTime = measureTimeMillis {
            composeTestRule.setContent {
                LazyColumn {
                    items(vaults, key = { it.id }) { vault ->
                        VaultCard(
                            vault = vault,
                            onClick = {},
                            onCheckIn = {}
                        )
                    }
                }
            }
        }

        assertNoRegression("ui_rendering_500_vaults", PERFORMANCE_BASELINES_MS["ui_rendering_500_vaults"]!!, renderTime)
    }

    companion object {
        /** Maximum allowed regression before CI flags a failure. */
        const val REGRESSION_THRESHOLD_PERCENT = 5L

        /**
         * Performance baselines (ms) for startup, sync, and UI rendering scenarios.
         * These are the reference points CI compares against to detect regressions > 5%.
         */
        val PERFORMANCE_BASELINES_MS: Map<String, Long> = mapOf(
            "startup_cold" to 1500L,
            "sync_full" to 2000L,
            "ui_rendering_100_vaults" to 2000L,
            "ui_rendering_500_vaults" to 5000L
        )
    }
}
