# Add project specific ProGuard rules here.
# See https://developer.android.com/studio/build/shrink-code for details.
#
# ---------------------------------------------------------------------------
# REVIEW NOTES (task #429)
# Reviewed against what is actually needed by:
#   - kotlinx.serialization (reflection-based $$serializer companions)
#   - Room (entity/DAO generated code)
#   - Ktor Android engine + WebSocket
#   - Hilt/Dagger generated components
#   - HiltWorker / WorkManager (CheckInReminderWorker, PendingActionSyncWorker)
#   - Firebase Cloud Messaging (TTLFirebaseMessagingService)
#   - kotlinx.coroutines
#
# Key findings and fixes vs. the previous revision:
#
# 1. Debug logging: android.util.Log calls are NOT stripped by default R8 rules.
#    We explicitly remove them below so that vault balances, JWT fragments, and
#    2FA secrets cannot leak via logcat in a release/production build.
#
# 2. Room rules previously referenced 'PendingCheckIn'/'PendingCheckInDao' which
#    do not exist. Fixed to 'PendingAction'/'PendingActionDao' in the services
#    package.
#
# 3. Added kotlinx.serialization rules for com.ethosprotocol.services.** to cover
#    DepositPayload and WithdrawPayload, which are @Serializable in that package.
#
# 4. Added @Keep annotations directly on all @Serializable data classes (Models.kt,
#    NotificationPreferences.kt, PendingAction.kt) as a belt-and-suspenders guard —
#    the ProGuard rules here and the @Keep annotations reinforce each other.
#
# 5. Added keep rules for kotlinx.serialization internal machinery, Firebase
#    Messaging, WorkManager workers, and coroutines volatile-field preservation.
#
# 6. Fixed stray 'dontnote' (missing leading '-') in the previous revision.
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# Strip debug/verbose/info logging in release builds
# This is a compile-time removal — Log.d/v/i calls are eliminated by R8 so
# they cannot leak sensitive data (token values, vault balances, etc.) even if
# a device is rooted or the APK is decompiled.
# Log.w and Log.e are kept; those represent genuine runtime warnings/errors
# that operators need in crash reports.
# ---------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ---------------------------------------------------------------------------
# kotlinx.serialization — internal machinery
# Keep the generated $$serializer companions/objects for our @Serializable
# models so reflection-based (de)serialization used by Ktor's
# ContentNegotiation/json() plugin keeps working under R8/minification.
# https://github.com/Kotlin/kotlinx.serialization/blob/master/rules/common.pro
# ---------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Keep the serialization library's own internal classes intact so the generated
# $$serializer descriptors, SerializersModule resolution, and polymorphic
# dispatch all remain functional after shrinking.
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# kotlinx.serialization — com.ethosprotocol.models package
# Vault, VaultEvent, AuthToken, and all other @Serializable data classes in
# the models package. Belt-and-suspenders alongside the @Keep annotations
# placed directly on those classes (#429).
# ---------------------------------------------------------------------------
-keep,includedescriptorclasses class com.ethosprotocol.models.**$$serializer { *; }
-keepclassmembers class com.ethosprotocol.models.** {
    *** Companion;
}
-keepclasseswithmembers class com.ethosprotocol.models.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep constructors and fields required for kotlinx.serialization's generated
# code. Intentionally narrower than a blanket { *; } rule — we keep only what
# the serialization framework genuinely requires, reducing the reflective surface
# of sensitive model classes such as AuthToken and PasskeyRegisterRequest.
-keepclassmembers class com.ethosprotocol.models.** {
    <init>(...);
    <fields>;
}

# ---------------------------------------------------------------------------
# kotlinx.serialization — com.ethosprotocol.services package
# DepositPayload and WithdrawPayload are @Serializable in this package and are
# serialized to JSON when enqueuing pending actions in Room.
# ---------------------------------------------------------------------------
-keep,includedescriptorclasses class com.ethosprotocol.services.**$$serializer { *; }
-keepclassmembers class com.ethosprotocol.services.** {
    *** Companion;
}
-keepclasseswithmembers class com.ethosprotocol.services.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class com.ethosprotocol.services.DepositPayload {
    <init>(...);
    <fields>;
}
-keepclassmembers class com.ethosprotocol.services.WithdrawPayload {
    <init>(...);
    <fields>;
}

# ---------------------------------------------------------------------------
# Room (entities / DAOs)
# The generated *_Impl DAO classes call into our entity/DAO interfaces via
# generated code, not reflection, but keep them explicitly for defense in
# depth against R8 stripping fields that Room writes/reads via cursor column
# name matching.
# ---------------------------------------------------------------------------
-keep class com.ethosprotocol.services.PendingAction { *; }
-keep interface com.ethosprotocol.services.PendingActionDao { *; }
-keep class com.ethosprotocol.services.PendingActionDatabase { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------------------
# Ktor client
# Ktor ships consumer ProGuard rules for its core artifacts, but the various
# platform engines it can multiplatform-target (OkHttp/CIO/Darwin/etc.) are
# referenced conditionally; we only ever use the Android engine, so silence
# missing-class warnings for the engines we don't pull in as dependencies.
# Keep volatile fields on Ktor classes — R8's field-removal pass can strip
# fields that are only written via coroutine state-machine mechanics.
# ---------------------------------------------------------------------------
-dontwarn io.ktor.client.engine.**
-dontwarn io.ktor.client.plugins.compression.**
-keep class io.ktor.client.engine.android.** { *; }
-keepclassmembers class io.ktor.** {
    volatile <fields>;
}

# Keep WebSocket-related Ktor classes used by VaultEventSocket.
-keep class io.ktor.client.plugins.websocket.** { *; }
-keep class io.ktor.websocket.** { *; }

# ---------------------------------------------------------------------------
# Firebase Cloud Messaging
# FCM looks up our FirebaseMessagingService subclass by name at runtime via
# the <service> manifest entry; if R8 renames it the service will not be found.
# ---------------------------------------------------------------------------
-keep class com.ethosprotocol.services.TTLFirebaseMessagingService { *; }
-keep class com.google.firebase.messaging.** { *; }
-dontwarn com.google.firebase.**

# ---------------------------------------------------------------------------
# WorkManager workers
# WorkManager's HiltWorkerFactory looks up worker classes by their canonical
# class name (stored as a string in the WorkRequest). Renaming them breaks
# dispatch. Both workers use @HiltWorker and are caught by the Hilt rule below,
# but we also keep them explicitly for clarity and forward-safety.
# ---------------------------------------------------------------------------
-keep class com.ethosprotocol.services.PendingActionSyncWorker { *; }
-keep class com.ethosprotocol.services.CheckInReminderWorker { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ---------------------------------------------------------------------------
# Hilt / Dagger
# The Hilt Gradle plugin + androidx.hilt artifacts already bundle consumer
# rules for their generated components; these extra rules are defense in
# depth for the generated entry points and our @HiltWorker classes.
# ---------------------------------------------------------------------------
-keep class dagger.hilt.internal.aggregatedroot.codegen.** { *; }
-keep class hilt_aggregated_deps.** { *; }
-keep,allowobfuscation @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel
-keep @androidx.hilt.work.HiltWorker class * extends androidx.work.ListenableWorker {
    <init>(...);
}
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper

# ---------------------------------------------------------------------------
# kotlinx.coroutines
# Coroutines state machines and dispatchers rely on volatile fields and
# internal class names that R8 must not strip or rename.
# The coroutines library ships its own consumer rules; these supplement them.
# ---------------------------------------------------------------------------
-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembers class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepclassmembers class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-dontwarn kotlinx.coroutines.debug.**
