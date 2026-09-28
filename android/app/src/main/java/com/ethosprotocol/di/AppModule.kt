package com.ethosprotocol.di

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.work.WorkManager
import com.ethosprotocol.BuildConfig
import com.ethosprotocol.api.ApiClient
import com.ethosprotocol.api.NetworkMonitor
import com.ethosprotocol.api.EncryptedTokenProvider
import com.ethosprotocol.api.OfflineCache
import com.ethosprotocol.api.TokenProvider
import com.ethosprotocol.services.AnalyticsTracker
import com.ethosprotocol.services.AppVersionChecker
import com.ethosprotocol.services.CrashReporter
import com.ethosprotocol.services.CredentialManagerFactory
import com.ethosprotocol.services.PendingActionDatabase
import com.ethosprotocol.services.PendingActionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideTokenProvider(impl: EncryptedTokenProvider): TokenProvider = impl

    @Provides @Singleton
    fun provideApiClient(
        tokenProvider: TokenProvider,
        networkMonitor: NetworkMonitor,
        offlineCache: OfflineCache
    ): ApiClient = ApiClient(tokenProvider, networkMonitor, offlineCache, BuildConfig.API_BASE_URL)

    @Provides @Singleton
    fun providePendingActionDatabase(@ApplicationContext context: Context): PendingActionDatabase =
        PendingActionDatabase.create(context)

    @Provides @Singleton
    fun providePendingActionDao(db: PendingActionDatabase): PendingActionDao =
        db.pendingActionDao()

    @Provides @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    /**
     * Production binding for [CredentialManagerFactory].
     *
     * Unit tests supply their own fake factory directly to [com.ethosprotocol.services.PasskeyService]
     * without going through Hilt, so no test module override is needed.
     */
    @Provides @Singleton
    fun provideCredentialManagerFactory(): CredentialManagerFactory =
        CredentialManagerFactory { activity -> CredentialManager.create(activity) }

    /**
     * Provides the [AppVersionChecker] used on startup to compare the locally
     * installed version against the latest published store version and to
     * surface update prompts (including forced updates for critical releases).
     */
    @Provides @Singleton
    fun provideAppVersionChecker(
        @ApplicationContext context: Context,
        apiClient: ApiClient
    ): AppVersionChecker = AppVersionChecker(context, apiClient)

    /**
     * Provides the [CrashReporter] used to capture uncaught exceptions, stack
     * traces and breadcrumbs, and to attach release/version context to every
     * reported event.
     */
    @Provides @Singleton
    fun provideCrashReporter(@ApplicationContext context: Context): CrashReporter =
        CrashReporter(context)

    /**
     * Provides the [AnalyticsTracker] used to record privacy-respecting session
     * analytics: session start/end, screen views and user actions (check-in,
     * search, filter). Events are batched before dispatch to reduce network
     * chatter and are only collected when the user has opted in.
     */
    @Provides @Singleton
    fun provideAnalyticsTracker(@ApplicationContext context: Context): AnalyticsTracker =
        AnalyticsTracker(context)
}
