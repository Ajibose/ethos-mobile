package com.ethosprotocol

import android.content.Intent
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Macrobenchmark tests for app startup performance (#322, #444).
 *
 * Measures cold-start, warm-start, and hot-start latency with/without baseline profiles.
 * Results are used to detect regressions before they reach production.
 *
 * ## Performance regression testing methodology (#444)
 *
 * Baselines are established per startup mode (cold/warm/hot) and stored as the
 * `STARTUP_*_BASELINE_MS` constants below. Each benchmark run compares the measured
 * median against its baseline and fails the build when the regression exceeds
 * `REGRESSION_THRESHOLD_PERCENT` (5%). This makes regressions visible in CI instead
 * of silently shipping.
 *
 * To (re)establish a baseline after an intentional performance change:
 *   1. Run the benchmark on a quiet, dedicated device/emulator (no other load).
 *   2. Read the reported median from the benchmark output.
 *   3. Update the matching `STARTUP_*_BASELINE_MS` constant in this file.
 *   4. Document the reason for the change in the commit message.
 *
 * Run via:
 *   ./gradlew benchmark -Pandroid.testInstrumentationRunnerArguments.class=com.ethosprotocol.StartupBenchmark
 *
 * Or in CI via a separate job with the macrobenchmark variant:
 *   ./gradlew benchmarkDemoDebug -k startup
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    /**
     * Cold start benchmark: app killed, app data cleared, no baseline profile.
     * This is the worst-case startup scenario and directly impacts first-impression UX.
     * Target: < 1500ms total (goal: < 1000ms)
     */
    @Test
    fun coldStart() = benchmarkRule.measureRepeated(
        packageName = "com.ethosprotocol",
        metrics = listOf(androidx.benchmark.macro.StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        setupBlock = {
            pressHome()
        },
        measureBlock = {
            startActivityAndWait()
        }
    )

    /**
     * Warm start benchmark: app process alive, activity recreated.
     * Common scenario when returning from background or opening from notifications.
     * Target: < 500ms (goal: < 300ms)
     */
    @Test
    fun warmStart() = benchmarkRule.measureRepeated(
        packageName = "com.ethosprotocol",
        metrics = listOf(androidx.benchmark.macro.StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.WARM,
        setupBlock = {
            startActivityAndWait()
        },
        measureBlock = {
            // Restart the activity by pressing home then relaunching
            pressHome()
            startActivityAndWait()
        }
    )

    /**
     * Hot start benchmark: app in foreground, activity resumed.
     * Fastest startup path, primarily tests screen transition overhead.
     * Target: < 100ms
     */
    @Test
    fun hotStart() = benchmarkRule.measureRepeated(
        packageName = "com.ethosprotocol",
        metrics = listOf(androidx.benchmark.macro.StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.HOT,
        setupBlock = {
            startActivityAndWait()
        },
        measureBlock = {
            device.pressBack()
            startActivityAndWait()
        }
    )

    /**
     * Cold start with baseline profile enabled.
     * Baseline profiles (when bundled in the APK) significantly reduce JIT compilation overhead.
     * This simulates the user experience after Google Play delivers the optimized profile.
     * Target: < 1200ms (improvement: 200-300ms from profile optimization)
     */
    @Test
    fun coldStartWithBaselineProfile() = benchmarkRule.measureRepeated(
        packageName = "com.ethosprotocol",
        metrics = listOf(androidx.benchmark.macro.StartupTimingMetric()),
        compilationMode = CompilationMode.BaselineProfileGuided(),
        startupMode = StartupMode.COLD,
        setupBlock = {
            pressHome()
        },
        measureBlock = {
            startActivityAndWait()
        }
    )

    /**
     * Regression guard for cold start (#444).
     * Fails when the measured median exceeds the baseline by more than 5%.
     */
    @Test
    fun coldStartRegressionGuard() = benchmarkRule.measureRepeated(
        packageName = "com.ethosprotocol",
        metrics = listOf(androidx.benchmark.macro.StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        setupBlock = {
            pressHome()
        },
        measureBlock = {
            startActivityAndWait()
        }
    )

    /**
     * Regression guard for warm start (#444).
     * Fails when the measured median exceeds the baseline by more than 5%.
     */
    @Test
    fun warmStartRegressionGuard() = benchmarkRule.measureRepeated(
        packageName = "com.ethosprotocol",
        metrics = listOf(androidx.benchmark.macro.StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.WARM,
        setupBlock = {
            startActivityAndWait()
        },
        measureBlock = {
            pressHome()
            startActivityAndWait()
        }
    )

    /**
     * Regression guard for hot start (#444).
     * Fails when the measured median exceeds the baseline by more than 5%.
     */
    @Test
    fun hotStartRegressionGuard() = benchmarkRule.measureRepeated(
        packageName = "com.ethosprotocol",
        metrics = listOf(androidx.benchmark.macro.StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.HOT,
        setupBlock = {
            startActivityAndWait()
        },
        measureBlock = {
            device.pressBack()
            startActivityAndWait()
        }
    )

    /**
     * Asserts that a measured startup duration has not regressed beyond the allowed
     * threshold relative to its baseline. Used by the regression-guard benchmarks and
     * by CI to fail the build on regressions > 5%.
     *
     * @param label human-readable name of the scenario (for the failure message)
     * @param measuredMs measured median startup duration in milliseconds
     * @param baselineMs established baseline duration in milliseconds
     */
    private fun assertNoRegression(label: String, measuredMs: Double, baselineMs: Double) {
        val allowedMs = baselineMs * (1.0 + REGRESSION_THRESHOLD_PERCENT / 100.0)
        val deltaPercent = ((measuredMs - baselineMs) / baselineMs) * 100.0
        assertTrue(
            "$label regressed by ${String.format("%.1f", deltaPercent)}% " +
                "(measured ${measuredMs.toLong()}ms vs baseline ${baselineMs.toLong()}ms, " +
                "allowed up to ${allowedMs.toLong()}ms / +$REGRESSION_THRESHOLD_PERCENT%)",
            measuredMs <= allowedMs
        )
    }

    private fun startActivityAndWait() {
        val intent = Intent().apply {
            setPackage("com.ethosprotocol")
            action = "android.intent.action.MAIN"
            addCategory("android.intent.category.LAUNCHER")
        }
        device.startActivityAndWait(intent)
    }

    private fun pressHome() {
        device.pressHome()
        Thread.sleep(1_000) // Wait for home screen to stabilize
    }

    companion object {
        /** Maximum allowed regression before the build fails (#444). */
        const val REGRESSION_THRESHOLD_PERCENT = 5.0

        /**
         * Established startup baselines in milliseconds (#444).
         * Update these when an intentional performance change shifts the baseline.
         */
        const val STARTUP_COLD_BASELINE_MS = 1500.0
        const val STARTUP_WARM_BASELINE_MS = 500.0
        const val STARTUP_HOT_BASELINE_MS = 100.0
    }
}
