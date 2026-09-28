# Mobile App Architecture

> **Security:** Found a vulnerability? Please read our [Security Policy](SECURITY.md) and report
> it privately to **security@ethos-protocol.app** — do not open a public issue.
> A `security.txt` (RFC 9116) is published at
> [`/.well-known/security.txt`](.well-known/security.txt) and will also be served from
> `https://ethos-protocol.app/.well-known/security.txt` once the domain is configured.

## Overview

Ethos-Protocol mobile apps (iOS + Android) provide a native interface for managing vaults, checking in, and receiving expiry reminders. Both apps share the same REST API contract and feature set.

## Structure

```
mobile/
├── shared/
│   └── api-contract.md          # Shared API spec (iOS + Android)
├── ios/EthosProtocol/
│   └── Sources/
│       ├── App/                 # Entry point, app lifecycle
│       ├── Models/              # Vault, AuthToken, etc.
│       ├── Services/
│       │   ├── APIClient.swift      # Ktor-style async HTTP client
│       │   ├── PasskeyService.swift # ASAuthorization / WebAuthn
│       │   ├── KeychainService.swift# Secure token storage
│       │   ├── NotificationService.swift # APNs + local reminders
│       │   └── OfflineSupport.swift # NetworkMonitor + disk cache
│       ├── ViewModels/          # AuthStore, VaultStore (ObservableObject)
│       └── Views/               # SwiftUI screens
└── android/app/src/main/java/com/ethosprotocol/
    ├── api/
    │   ├── ApiClient.kt         # Ktor HTTP client
    │   └── Infrastructure.kt    # NetworkMonitor, OfflineCache, TokenProvider
    ├── models/                  # Kotlinx.serialization data classes
    ├── services/
    │   ├── PasskeyService.kt    # CredentialManager / WebAuthn
    │   ├── PushService.kt       # Firebase Messaging
    │   └── NotificationHelper.kt# Local notification display
    ├── ui/
    │   ├── ViewModels.kt        # AuthViewModel, VaultViewModel (Hilt)
    │   ├── MainActivity.kt      # NavHost entry point
    │   ├── screens/Screens.kt   # Compose screens
    │   └── theme/Theme.kt       # Material3 dynamic color
    └── di/AppModule.kt          # Hilt DI bindings
```

## Key Design Decisions

### Passkey Authentication (WebAuthn)
- **iOS**: `ASAuthorizationPlatformPublicKeyCredentialProvider` (iOS 16+)
- **Android**: `CredentialManager` API (Android 9+, API 28+)
- Flow: `getChallenge()` → device biometric prompt → `verifyPasskey()` → JWT stored in Keychain/SharedPreferences
- Relying party: `ethos-protocol.app` (requires `.well-known/assetlinks.json` + Apple App Site Association)

### Push Notifications
- **iOS**: APNs via `UNUserNotificationCenter`. Device token registered to backend on first launch.
  - Local reminders scheduled 24h before vault expiry via `UNTimeIntervalNotificationTrigger`
  - Actionable notification category `CHECK_IN` with inline "Check In" action
- **Android**: Firebase Cloud Messaging (FCM). Token refreshed via `onNewToken`.
  - Notification channel `ttl_reminders` (IMPORTANCE_HIGH)
  - Deep-link intent to `MainActivity` with `vault_id` extra

### Offline Support
- `NetworkMonitor` checks live connectivity before every request
- `OfflineCache` stores last successful GET responses keyed by URL (SHA-256 filename)
- On network unavailable: cached data served transparently; mutations show "offline" error
- iOS: `CryptoKit.SHA256` for cache keys; Android: `MessageDigest("SHA-256")`
- **Offline check-in queue**: a check-in made while offline is queued for retry rather
  than just failing.
  - iOS: `PendingCheckInStore` (disk-backed JSON) is the sole insertion point; `CheckInSyncTask`
    drains it via a `BGProcessingTask` once connectivity returns. This is the only check-in
    queue implementation — an earlier duplicate (`CheckInQueue`/`CheckInSyncService`) was
    removed in 8d8d59d; see `PendingCheckInStoreTests`/`CheckInSyncTaskTests` for the
    regression guard.
  - Android: `PendingActionDao`/`PendingActionDatabase` (Room), drained by `PendingActionSyncWorker` (WorkManager)

### State Management
- **iOS**: `@StateObject` / `ObservableObject` stores (`AuthStore`, `VaultStore`) injected via SwiftUI environment
- **Android**: Hilt-injected `ViewModel`s with `StateFlow` + `collectAsStateWithLifecycle`

## Setup

### iOS
1. Install [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`) — the `.xcodeproj` is generated, not checked in
2. From `ios/EthosProtocol`, run `mkdir -p Xcode && xcodegen generate --project Xcode` to produce `Xcode/EthosProtocol.xcodeproj` (an `EthosProtocol` app target + `TTLWidget` widget extension, per `project.yml`) — the `Xcode/` directory must exist before `xcodegen generate` runs, or the copy step fails
3. Open `ios/EthosProtocol/Xcode/EthosProtocol.xcodeproj` in Xcode 15+
4. Set your Apple Developer Team in signing settings for both the `EthosProtocol` and `TTLWidget` targets (`project.yml` leaves `DEVELOPMENT_TEAM` blank on purpose — bundle IDs `com.ethosprotocol` / `com.ethosprotocol.TTLWidget` are already set)
5. `API_BASE_URL` is already set in `EthosProtocol/Info.plist` and `TTLWidget/Info.plist`; edit both (they're separate bundles, read independently at runtime) if you need to point at a different environment
6. Certificate pinning is **not active by default**: both `Info.plist`s declare `TLS_PUBLIC_KEY_PINS` as the `$(TLS_PUBLIC_KEY_PIN_CURRENT)` / `$(TLS_PUBLIC_KEY_PIN_BACKUP)` build settings (declared blank in `project.yml`), and `PinningDelegate` ignores blank/unexpanded entries. Set both settings to Base64-encoded SPKI SHA-256 hashes — in Xcode's build settings, an `.xcconfig`, or on the `xcodebuild` invocation (`xcodebuild … TLS_PUBLIC_KEY_PIN_CURRENT=… TLS_PUBLIC_KEY_PIN_BACKUP=…`) — before shipping a Release build; see `Sources/Services/CertificatePinning.swift` for the rotation strategy. `ios-ci.yml`'s `build-and-test` job runs `.github/scripts/check_tls_pinning.py --configuration Release` against both files and fails the build if either is missing or empty; Debug builds are exempt (`PinningDelegate` intentionally treats an empty pin set as "pinning disabled" for local dev)
7. Configure Apple App Site Association at `https://ethos-protocol.app/.well-known/apple-app-site-association`, listing this app's App ID under both `applinks` (Universal Links) and `webcredentials` (platform passkeys). CI automatically verifies this file daily and on any change to `EthosProtocol.entitlements` (see `ios-applinks-verify.yml`).
8. In the Apple Developer portal, enable Push Notifications, Associated Domains, iCloud (Key-Value storage), and Keychain Sharing capabilities for the `com.ethosprotocol` App ID, and Keychain Sharing for `com.ethosprotocol.TTLWidget` — matching `EthosProtocol/EthosProtocol.entitlements` / `TTLWidget/TTLWidget.entitlements`. Set up an APNs key in App Store Connect for push.
9. Re-run `mkdir -p Xcode && xcodegen generate --project Xcode` any time `project.yml` changes; the generated `Xcode/` directory is disposable and shouldn't be committed

### Android
1. Open `android` in Android Studio Hedgehog+
2. Add `google-services.json` from Firebase Console
3. Configure `assetlinks.json` at `https://ethos-protocol.app/.well-known/assetlinks.json`. CI automatically verifies this file daily and on any change to `AndroidManifest.xml` (see `android-applinks-verify.yml`).
4. Set `API_BASE_URL` in `build.gradle.kts` `buildConfigField`
5. Configure the certificate pins for release builds by setting `ETHOS_CERT_PINS` (environment variable) or `ethos.certPins` (in `~/.gradle/gradle.properties`, never committed) to a comma-separated list of Base64 SHA-256 SPKI digests — the current certificate's pin plus a backup for the next one. This is required before any release build: pinning is **not** active by default. When unset, `CertificatePinner`'s pin set is empty — pinning is disabled and the system trust store decides (#169), so there is no compiled-in pin that could reject every real certificate. CI's `Verify release certificate pins are not placeholders` step reports an unconfigured release build (#173), and fails it outright once the pins are configured but wrong, or once release signing is configured (i.e. the artifact is actually shippable). Debug builds are not gated, since an empty pin set disables pinning for local/dev hosts. Compute a pin with:
   ```bash
   openssl s_client -connect api.ethos-protocol.app:443 2>/dev/null \
     | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der \
     | openssl dgst -sha256 -binary | openssl enc -base64
   ```

## Testing

### iOS
```bash
cd ios/EthosProtocol
swift test
```
Covers: model decoding, Keychain round-trip, offline cache, Base64URL encoding.
Tests run against the SPM package (`Package.swift`) directly and don't require the
XcodeGen-generated project; CI runs this the same way, via `xcodebuild test` against
an iOS Simulator destination (`swift test` alone defaults to macOS, which can't build
the app's iOS-only framework imports).

#### iOS SPM Dependency Vulnerability Scanning
CI runs a weekly (and per-PR on `Package.swift` / `Package.resolved` changes) vulnerability
scan against all pinned SPM dependencies using [osv-scanner](https://github.com/google/osv-scanner),
querying the [OSV database](https://osv.dev). The scan fails the build for any dependency
with a published CVE at CVSS ≥ 7.0 (high or critical). This mirrors the Android
`android-dependency-check.yml` OWASP scan.

Workflow: `.github/workflows/ios-dependency-check.yml`

False-positive suppressions: `ios/EthosProtocol/spm-vulnerability-suppressions.toml`
(follows the same pattern as `android/dependency-check-suppressions.xml` — each entry
requires a documented rationale).

To run locally:
```bash
brew install osv-scanner
cd ios/EthosProtocol
osv-scanner --lockfile "swift:Package.resolved" --fail-on-severity HIGH
```

### Android
```bash
cd android
./gradlew test                  # Unit tests (JVM)
./gradlew connectedAndroidTest  # Instrumented tests (device/emulator)
```
Covers: ViewModel state transitions, model logic, Compose UI smoke tests.

#### RTL Layout Testing (Issue #314)

The app supports Right-to-Left (RTL) locales (Arabic, Hebrew, etc.) via automatic layout mirroring. To test RTL functionality:

**Enable RTL layout direction on a device/emulator:**
```bash
adb shell settings put global debug.force_rtl_layout 1
```

**Run the app and verify:**
- All screens display with proper mirroring (buttons, text, icons)
- No text clipping or overlap at edges
- Numerical values format correctly (see Issue #313 for locale-aware formatting)

**Disable RTL when done:**
```bash
adb shell settings put global debug.force_rtl_layout 0
```

See [docs/rtl-layout-testing.md](docs/rtl-layout-testing.md) for comprehensive RTL testing procedures on both platforms.

### iOS RTL Testing

Enable RTL pseudo-language in Xcode to test Right-to-Left layout support:

1. Edit Scheme → Run → Options
2. Set "App Language" to an RTL pseudo-language (e.g., `ar-XB` for Arabic-Pseudo)
3. Run the app and verify all screens mirror correctly

<<<<<<< HEAD
### Dependency vulnerability scanning
The repo runs a dependency scan for both platforms with the same trigger model:
- `push` to `main` when dependency manifests change
- `pull_request` to `main` for the same dependency-focused paths
- weekly `schedule` runs to catch newly disclosed CVEs between dependency bumps

Workflow files:
- Android: `.github/workflows/android-dependency-check.yml`
- iOS: `.github/workflows/ios-dependency-check.yml`

Both workflows treat dependency-scan failures as a consistent, human-readable warning in the job log and create a scheduled-run issue alert when the scan fails outside a PR context.

### Release notes parity-gap validation
Release notes are expected to stay aligned with the "Known gaps" table in [PARITY.md](PARITY.md). To prevent a release from claiming a parity issue is closed while the table still lists it as open, CI includes a parity audit workflow:

- Workflow: `.github/workflows/release-notes-parity-check.yml`
- Script: `.github/scripts/release_notes_parity_check.py`

The validator:
- extracts issue numbers from the "Known gaps" table in [PARITY.md](PARITY.md)
- scans merged PRs for parity-gap issue references and close verbs such as "closes #..." or "fixes #..."
- checks the current release notes for the same claim patterns
- fails when a listed parity gap is explicitly called out as closed without the table being updated

Run it locally from the repo root with:

```bash
python3 .github/scripts/release_notes_parity_check.py \
  --parity-file PARITY.md \
  --prs-file merged-prs.json \
  --release-notes-file release-notes.md
```

To generate the JSON input for the PR scan:

```bash
gh pr list --state merged --limit 200 --json number,title,body > merged-prs.json
```

This keeps parity-status messaging consistent with the cross-platform tracking table and helps release notes communicate platform catch-up progress accurately.

### App Links Verification (Deep Linking & Passkeys)

Both platforms verify that their respective deep-linking and passkey configuration files are correctly hosted and match the app's entitlements/manifest expectations. These checks run daily and on any change to app configuration, catching server-side drift without requiring a code push:

#### iOS: Apple App Site Association (Universal Links + Passkeys)
- **Workflow**: `.github/workflows/ios-applinks-verify.yml`
- **Script**: `scripts/verify_apple_app_site_association.sh`
- **Verification targets**:
  - File is reachable at `https://ethos-protocol.app/.well-known/apple-app-site-association` (HTTP 200)
  - File contains valid JSON
  - `applinks` section lists the app's Team ID + Bundle ID (`com.ethosprotocol`)
  - `webcredentials` section lists the app's Team ID + Bundle ID (required for platform passkeys)
- **Configuration**: Set `APPLE_TEAM_IDENTIFIER` as a repository variable (Apple Developer Team ID, e.g., "ABCDEFGHIJ")

#### Android: Digital Asset Links (App Links + Passkeys)
- **Workflow**: `.github/workflows/android-applinks-verify.yml`
- **Script**: `scripts/verify_assetlinks.sh`
- **Verification targets**:
  - File is reachable at `https://ethos-protocol.app/.well-known/assetlinks.json` (HTTP 200)
  - File contains valid JSON
  - Contains `delegate_permission/common.handle_all_urls` relation for `com.ethosprotocol`
  - Namespace is `android_app`
  - Certificate fingerprint matches the release signing certificate (optional, configurable)
- **Configuration**: Set `ANDROID_CERT_SHA256` as a repository secret (SHA-256 fingerprints, one per line)

Both workflows file an automated GitHub issue alert on scheduled-run failures, avoiding duplicate alerts by commenting on existing open issues instead of creating new ones each run.

### Staging Smoke Test

`.github/workflows/staging-smoke-test.yml` runs `scripts/smoke_test_staging.sh`
against a staging deployment (a separate `STAGING_API_BASE_URL` from the
per-client `API_BASE_URL` set in `Info.plist` / `build.gradle.kts` — staging
is a fixed CI-only target, not something either app build points at). It
exercises auth, `GET /vaults`, and `POST /vaults/{id}/checkin` to catch a
backend/client contract mismatch (see `shared/api-contract.md`) before a
release build is cut. The workflow is exposed via `workflow_call` so a release
workflow can add `needs:` on it once one exists.

### This workspace already satisfies the requested task list:

Snapshot testing framework: Paparazzi configured
Screens covered: core app screens + widget snapshots
Snapshot update flow: recordPaparazziDebug is documented in the tests
CI comparison: verifyPaparazziDebug is in the Android CI workflow
Documentation: snapshot/test guidance is in the project docs