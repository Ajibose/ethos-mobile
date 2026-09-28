package com.ethosprotocol

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.ethosprotocol.analytics.SessionAnalytics
import com.ethosprotocol.crash.CrashReporter
import com.ethosprotocol.utils.AppVersionUpdateChecker
import com.ethosprotocol.utils.StartupPerformance
import com.ethosprotocol.widget.VaultWidgetUpdateWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class EthosProtocolApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var appVersionUpdateChecker: AppVersionUpdateChecker

    @Inject lateinit var crashReporter: CrashReporter

    @Inject lateinit var sessionAnalytics: SessionAnalytics

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        StartupPerformance.markAppStart()

        // Initialize crash reporting as early as possible so that any crash
        // during startup is captured with stack traces, device context and
        // release/version tracking (#425).
        crashReporter.initialize(this)

        // Begin privacy-respecting session analytics tracking (#426). The
        // session start timestamp is recorded here and the session is closed
        // (with a flush of any batched events) when the app is terminated.
        sessionAnalytics.startSession()

        // Defer non-critical initialization (widget updates) to after first frame
        // to reduce cold-start time (#317). Schedule after ~2 seconds to ensure
        // the app is fully rendered and responsive.
        Handler(Looper.getMainLooper()).postDelayed({
            VaultWidgetUpdateWorker.schedule(this)
        }, 2000)

        // Check for new app versions on startup (#424). Deferred so the version
        // check never blocks cold start; the checker compares the installed
        // version against the latest store version and surfaces an update prompt
        // (with a store link, and a forced update for critical releases).
        Handler(Looper.getMainLooper()).postDelayed({
            appVersionUpdateChecker.checkForUpdates()
        }, 3000)
    }

    override fun onTerminate() {
        // Close the current analytics session and flush any pending batched
        // events before the process is torn down (#426).
        sessionAnalytics.endSession()
        super.onTerminate()
    }
}
