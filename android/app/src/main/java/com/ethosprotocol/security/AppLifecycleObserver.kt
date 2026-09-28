package com.ethosprotocol.security

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Process-level lifecycle observer that forwards app-foreground / app-background
 * events to [SessionLockManager] and records session analytics boundaries.
 *
 * Register once with [androidx.lifecycle.ProcessLifecycleOwner] — e.g. in
 * `MainActivity.onCreate`:
 *
 * ```kotlin
 * ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleObserver())
 * ```
 *
 * `ProcessLifecycleOwner` represents the entire app process, so `onStart` fires
 * when any Activity is started (app foregrounded) and `onStop` fires only when
 * every Activity has stopped (app fully backgrounded), which is the correct
 * granularity for session-lock decisions and for session start/end tracking.
 */
class AppLifecycleObserver : DefaultLifecycleObserver {

    /**
     * Called when the app moves to the foreground (at least one Activity is
     * started / resumed). Delegates to [SessionLockManager.onAppForeground]
     * which checks whether the inactivity timeout has been exceeded, and marks
     * the start of an analytics session.
     */
    override fun onStart(owner: LifecycleOwner) {
        SessionLockManager.onAppForeground()
        SessionAnalyticsTracker.onSessionStart()
    }

    /**
     * Called when the app moves to the background (all Activities have stopped).
     * Delegates to [SessionLockManager.onAppBackground] which records the
     * current time so the elapsed interval can be measured on the next foreground,
     * and closes the current analytics session.
     */
    override fun onStop(owner: LifecycleOwner) {
        SessionLockManager.onAppBackground()
        SessionAnalyticsTracker.onSessionEnd()
    }
}
