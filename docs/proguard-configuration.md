# ProGuard / R8 Configuration (#429)

Release builds run R8 (the replacement for ProGuard) with
`isMinifyEnabled = true`. Rules live in
`android/app/proguard-rules.pro` alongside the AGP default
`proguard-android-optimize.txt`.

## What is protected and why

| Area | Rule type | Reason |
|---|---|---|
| `com.ethosprotocol.models.**` | `-keep` constructors/fields + `$$serializer` | kotlinx.serialization generates companion objects that R8 must not rename |
| `com.ethosprotocol.services.DepositPayload/WithdrawPayload` | `-keep` + `$$serializer` | @Serializable classes in the services package (serialized to JSON for Room) |
| `PendingAction` / `PendingActionDao` / `PendingActionDatabase` | `-keep class { *; }` | Room cursor-column mapping is by field name; renaming breaks it |
| `TTLFirebaseMessagingService` | `-keep class { *; }` | FCM resolves the service by class name from the manifest |
| `PendingActionSyncWorker` / `CheckInReminderWorker` | `-keep class { *; }` | WorkManager's HiltWorkerFactory resolves workers by canonical class name |
| Hilt generated components | `-keep` aggregated-root/deps | Hilt component wiring uses generated class names |
| Ktor Android engine + WebSocket | `-keep` + `-dontwarn` | Ktor references optional engines not on the classpath |
| kotlinx.coroutines | volatile fields + dispatcher factories | Coroutine state machines rely on volatile fields R8 would otherwise strip |
| `android.util.Log v/d/i` | `-assumenosideeffects` | Compile-time removal prevents sensitive data leaking via logcat |

## @Keep annotations

Every `@Serializable` data class also carries `@Keep` directly on the
class declaration. This is belt-and-suspenders: if a future refactor
moves a class to a package not covered by the ProGuard wildcard, the
annotation still protects it.

Classes annotated: all models in `com.ethosprotocol.models` (Vault,
AuthToken, VaultEvent, all 2FA/passkey/recovery models, VaultPage, etc.),
`NotificationPreferences`, and `DepositPayload`/`WithdrawPayload`/`PendingAction`
in `com.ethosprotocol.services`.

## Testing a release build

```bash
cd android
./gradlew assembleRelease   # requires signing config or just checks shrinking
```

Run the app from the release APK on a physical device or emulator and
exercise: login, vault list, check-in, 2FA, push notification receipt.
Any `SerializationException` or `ClassNotFoundException` in the release
build that does not appear in debug indicates a missing keep rule.

Useful logcat filter for serialization failures:

```
adb logcat -s "EthosProtocol" | grep -i "serial\|classnotfound\|reflect"
```

## Adding new serializable classes

1. Annotate the class with both `@Serializable` and `@Keep`.
2. If the class is outside `com.ethosprotocol.models` or
   `com.ethosprotocol.services`, add a matching `-keep,includedescriptorclasses`
   rule for the `$$serializer` companion and a `-keepclassmembers` rule for
   constructors and fields to `proguard-rules.pro`.
3. Run a release build and smoke-test serialization before merging.
