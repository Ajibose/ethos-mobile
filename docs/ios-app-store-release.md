# iOS App Store release automation

Automates App Store Connect submission for the iOS app (#463): signed build, TestFlight
upload, "What's New" generation, store listing/screenshot upload, and App Review submission.

- Workflow: [`.github/workflows/ios-app-store-release.yml`](../.github/workflows/ios-app-store-release.yml)
- Lanes: [`ios/EthosProtocol/fastlane/Fastfile`](../ios/EthosProtocol/fastlane/Fastfile) (fastlane 2.240.1, Ruby 3.3.12, both pinned)
- Release notes: [`.github/scripts/generate_release_notes.py`](../.github/scripts/generate_release_notes.py) (tests in `.github/scripts/tests/`, run by iOS CI)

Android / Play Store is not covered yet (see [Follow-ups](#follow-ups)).

## How a release flows

```
git tag v1.2.0 ──► testflight job (macos-26, Xcode 26.6)
                    1. validate: tag == MARKETING_VERSION, release notes, review config, store assets
                    2. xcodegen → match (read-only) → build_app (Release, TLS pins from ETHOS_CERT_PINS)
                    3. verify pins in the built Info.plists (check_tls_pinning.py)
                    4. upload_to_testflight (internal testers, "What to Test" = release notes)
                          │
                          ▼ only if review submission is requested, or metadata/screenshots changed
                   app-store job (ubuntu) ── waits for approval on the `app-store` environment
                    5. deliver: set What's New, upload metadata/screenshots (if requested)
                    6. submit for review (if requested): precheck, then submission;
                       automatic_release: false by default
```

**By default a release goes to TestFlight only.** Nothing reaches App Review unless someone
asks for it (the `submit_for_review` input, or the `IOS_SUBMIT_FOR_REVIEW_ON_TAG` variable
for tags) **and** a required reviewer approves the `app-store` environment. After Apple
approves the build, a maintainer still presses **Release** in App Store Connect, unless
`automatic_release: true` is set in `review_submission.yml`.

The workflow never runs on `pull_request`. Uploads (non-dry runs) are refused from any ref
other than `main` or a `vX.Y.Z` tag. One release runs at a time (`concurrency`).

## Cutting a release

1. Bump `MARKETING_VERSION` for **both** targets (`EthosProtocol` and `TTLWidget`) in
   `ios/EthosProtocol/project.yml`. App Store Connect rejects an extension whose version
   differs from the app's. Leave `CURRENT_PROJECT_VERSION` alone: CI sets the build number.
2. Optionally commit hand-written notes to `ios/EthosProtocol/fastlane/release_notes/<version>/en-US.txt`.
3. Merge to `main`, then tag that commit:
   ```sh
   git tag v1.2.0 && git push origin v1.2.0
   ```
4. The tag builds and uploads to TestFlight. To submit for review, run the workflow manually
   (Actions > iOS App Store Release > Run workflow) on the tag with `dry_run` off and
   `submit_for_review` on, then approve the `app-store` deployment.

   This builds and uploads a new TestFlight build number of the same version, which is then
   submitted. To submit straight from the tag push instead, set the repository variable
   `IOS_SUBMIT_FOR_REVIEW_ON_TAG=true`. The approval gate still applies.

### Manual run inputs

| Input | Default | Effect |
| --- | --- | --- |
| `dry_run` | **true** | Validates everything, makes an **unsigned** Release build, and walks the App Store lane in dry-run mode. Signs nothing and uploads nothing. |
| `submit_for_review` | false | After the TestFlight upload, the `app-store` job submits that build for review (after approval). |
| `upload_metadata` | false | Upload listing text from `fastlane/metadata/`. Also automatic when that folder changed since the previous tag. |
| `upload_screenshots` | false | Replace screenshots from `fastlane/screenshots/`. Also automatic when that folder changed since the previous tag. |
| `release_notes` | empty | Replaces the generated "What's New" for every locale (per-locale override files still win). |

### Dry run

Run the workflow with `dry_run` checked (the default). It checks:

- the tag/version match,
- release notes generation (and the length limit),
- the review answers (strictly, if `submit_for_review` is also checked),
- metadata/screenshot folders, using deliver's own validator,
- that the Release configuration compiles with the TLS pins (pin check runs if `ETHOS_CERT_PINS` is set),
- which secrets are missing (names only).

Locally, without any credentials (fastlane runs fine on Linux/Windows via Docker for everything except the build):

```sh
cd ios/EthosProtocol
bundle install
bundle exec fastlane ios validate
bundle exec fastlane ios beta dry_run:true                  # + unsigned Release build on macOS
bundle exec fastlane ios app_store dry_run:true submit_for_review:true build_number:1
```

## Secrets and variables

Settings > Secrets and variables > Actions. Nothing below is ever committed. The lanes read
them from the environment and fail with the list of missing names.

| Name | Kind | Used by | What it is / how to create it |
| --- | --- | --- | --- |
| `ASC_KEY_ID` | secret | both jobs | App Store Connect API key ID (see below). |
| `ASC_ISSUER_ID` | secret | both jobs | Issuer ID shown above the key list. |
| `ASC_KEY_P8_BASE64` | secret | both jobs | The downloaded `AuthKey_<KEY_ID>.p8`, base64-encoded: `base64 -i AuthKey_XXXX.p8 \| tr -d '\n'`. |
| `MATCH_GIT_URL` | secret | testflight | Private git repo holding the encrypted certificates/profiles, e.g. `https://github.com/ethos-protocol/ios-certificates.git`. |
| `MATCH_PASSWORD` | secret | testflight | Passphrase that encrypts the match repo (chosen when running match the first time). |
| `MATCH_GIT_BASIC_AUTHORIZATION` | secret | testflight | For an `https` match repo: `printf 'x-access-token:<token>' \| base64`, where the token is a fine-grained PAT (or GitHub App token) with **read-only Contents** access to that one repo. |
| `ETHOS_CERT_PINS` | secret (exists) | testflight | Already used by Android CI: comma-separated Base64 SPKI SHA-256 pins for `api.ethos-protocol.app`. iOS uses the first two (current, backup). A Release build refuses to ship with fewer than two. |
| `APPLE_TEAM_IDENTIFIER` | variable (exists) | testflight | 10-character Apple Developer Team ID (already used by `ios-applinks-verify.yml`). Injected at build time, so `DEVELOPMENT_TEAM` in `project.yml` stays blank. |
| `IOS_SUBMIT_FOR_REVIEW_ON_TAG` | variable (optional) | testflight | `true` to request review submission on every tag push (still gated by the environment). |

### App Store Connect API key

App Store Connect > Users and Access > Integrations > App Store Connect API > Team Keys > **+**.

- **Role: App Manager** is the intended minimum: it covers TestFlight uploads, build
  numbers, metadata, screenshots and review submission, and CI only runs match with
  `readonly: true`, so it never creates certificates. If match can't read the signing
  assets with that role on your account, use Admin. The CI key doesn't need more.
- Download the `.p8` once (Apple won't show it again), store it in a password manager, and
  set the three `ASC_*` secrets. The workflow never writes the key to disk. It's passed to
  fastlane as base64 content.
- An API key avoids Apple ID passwords and 2FA sessions entirely.

### `app-store` environment (the approval gate)

Settings > Environments > **New environment** `app-store`:

1. **Required reviewers**: the maintainers allowed to send builds to App Review (enable
   "Prevent self-review" if available).
2. **Deployment branches and tags**: "Selected branches and tags", with `main` and `v*`.
3. Optional hardening: move `ASC_*` into environment secrets and give the testflight job a
   second environment (e.g. `testflight`) with its own copy. The workflow as written reads
   repository-level secrets in both jobs.

The `app-store` job only runs (and only asks for approval) when review submission was
requested or listing files changed.

## One-time Apple setup

1. **App IDs** (Certificates, Identifiers & Profiles), matching `project.yml`:
   - `com.ethosprotocol`: Associated Domains, Push Notifications, iCloud (key-value storage).
     These come from `EthosProtocol.entitlements`.
   - `com.ethosprotocol.TTLWidget`: no extra capabilities (keychain sharing needs none).
2. **App record** in App Store Connect for `com.ethosprotocol` (My Apps > **+**). The first
   version's listing (description, privacy policy URL, age rating, App Privacy) is filled in
   there once.
3. **Signing with fastlane match.** Once, from a Mac, with an **Admin** (or Account Holder)
   Apple ID, create the distribution certificate and App Store profiles in the private match
   repo:
   ```sh
   cd ios/EthosProtocol && bundle install
   bundle exec fastlane match appstore \
     --app_identifier com.ethosprotocol,com.ethosprotocol.TTLWidget \
     --git_url <MATCH_GIT_URL> --team_id <APPLE_TEAM_IDENTIFIER>
   ```
   CI then only reads from that repo. Re-run the same command (without `--readonly`) when a
   profile needs regenerating, e.g. after adding a capability.

   *Alternative:* Xcode cloud-managed signing (`-allowProvisioningUpdates` with the API
   key) would remove the match repo, but needs an Admin-role key in CI. This PR uses match
   so the CI key can stay App Manager.
4. **Answer `ios/EthosProtocol/fastlane/review_submission.yml`** (see below).

## Release notes

Generated by `.github/scripts/generate_release_notes.py` from `git log --first-parent`
between the previous `v*` tag and the release commit. This repo merges PRs with merge
commits, so each PR contributes its title once.

- **Kept:** `feat:` → "New", `perf:` → "Improvements", `fix:` → "Fixes".
- **Dropped:** `chore`, `ci`, `test`, `docs`, `build`, `refactor`, `style`, non-conventional
  titles, `Merge branch` sync commits, Android-only scopes (`fix(android): ...`), and entries
  that are only issue references (`fix: address #1, #2`).
- `#123` references are stripped, duplicates collapsed.
- Trimmed to the App Store's **4,000-character** limit by dropping whole bullets (Fixes
  first), never mid-sentence. With nothing user-facing, it falls back to "Bug fixes and
  performance improvements."
- **Locales:** every locale folder in `fastlane/metadata/` gets notes; locales without their
  own text use the default locale's (`en-US`).
- **Overrides** (highest first): `fastlane/release_notes/<version>/<locale>.txt`, then the
  `release_notes` workflow input, then generated. Overrides over 4,000 characters fail the
  run instead of being cut.

The same text is the TestFlight "What to Test" note. "What's New" is skipped automatically on
the app's **first** App Store version, where App Store Connect doesn't allow it.

> The first release has no previous tag, so the generated notes cover the project's entire
> history. Use an override for `1.0.0`.

## Screenshots and metadata

See [`fastlane/screenshots/README.md`](../ios/EthosProtocol/fastlane/screenshots/README.md)
and [`fastlane/metadata/README.md`](../ios/EthosProtocol/fastlane/metadata/README.md).

- Screenshots are versioned in git under `fastlane/screenshots/<locale>/`. The app supports
  iPhone and iPad, so review needs **6.9" iPhone** (1320x2868 or 1290x2796) and **13" iPad**
  (2064x2752 or 2048x2732) sets. `validate` warns when either is missing and fails on
  unsupported sizes, bad folder names or more than 10 per device.
- Screenshot *generation* is not automated (no `fastlane snapshot`/UI-test capture exists
  yet). Add images by hand.
- Listing text stays managed in App Store Connect until locale folders are added to
  `fastlane/metadata/`.
- Uploads run only when the folder changed since the previous release tag, or when the
  matching input is set. An upload requested with an empty folder is refused, because
  `overwrite_screenshots` would otherwise clear the live sets.

## Review submission

`bundle exec fastlane ios app_store submit_for_review:true build_number:N` runs deliver
with:

- `run_precheck_before_submit: true` (in-app purchase checks are skipped: fastlane's
  precheck can't inspect IAPs when authenticated with an API key),
- `automatic_release` / `phased_release` from `review_submission.yml` (both `false` by default),
- `submission_information` from `review_submission.yml`.

**Maintainers must confirm these answers.** They ship as `CONFIRM`, and submission is
refused until each is `true` or `false`:

| Key | Question | Note |
| --- | --- | --- |
| `export_compliance_uses_encryption` | Does the app use non-exempt encryption? | `Info.plist` already declares `ITSAppUsesNonExemptEncryption = false` (HTTPS/TLS only). The lane fails if the two disagree. |
| `content_rights_contains_third_party_content` | Does the app contain, show or access third-party content? | Legal answer for the publisher. |
| `app_privacy_and_tracking_declared` | Is App Store Connect > App Privacy (data collection, tracking/IDFA) complete and accurate? | Apple no longer takes IDFA answers at submission. This is an acknowledgement gate. |

## Versioning and build numbers

- Version: `MARKETING_VERSION` in `project.yml` (single source of truth; tag `v<version>`).
- Build number: latest TestFlight build number + 1, queried at build time, so it stays
  unique across re-runs and manual uploads. Locally, `BUILD_NUMBER=42` overrides it.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `Tag vX does not match MARKETING_VERSION Y` | Bump both targets in `project.yml`, commit, and tag that commit. Delete the wrong tag (`git push --delete origin vX`). |
| `Missing required secrets/variables: ...` | Add the listed names (see the table above). Fork runs never have them, which is expected. |
| `Xcode_26.6.app is not on this runner image` | The step lists installed versions. Update `XCODE_VERSION` in the workflow. |
| match: `No matching provisioning profiles found` / `Could not decrypt` | Run the one-time `match appstore` command. Check `MATCH_PASSWORD` and the PAT's access to the match repo. |
| Provisioning profile doesn't support a capability/entitlement | Enable it on the App ID, then re-run `match appstore` (not read-only) to regenerate. |
| `ETHOS_CERT_PINS must list at least two pins` or `check_tls_pinning.py` fails | Set current and backup pins (see `docs/cert-pin-rotation-runbook.md`). |
| Upload rejected: `ITMS-90474` (iPad multitasking orientations) | The app targets iPad (`TARGETED_DEVICE_FAMILY: "1,2"`) but is portrait-only. Support all iPad orientations, or target iPhone only. That's an app change, not a pipeline change. |
| `The bundle version must be higher than the previously uploaded version` | Something uploaded a higher build number manually. The next run picks it up automatically; or set `BUILD_NUMBER`. |
| `review_submission.yml: unconfirmed answers` | Answer the `CONFIRM` values (see [Review submission](#review-submission)). |
| `Cannot submit for review - A review submission is already in progress` | Withdraw or finish the existing submission in App Store Connect, then re-run. |
| precheck fails | Read the rule it names (e.g. mentions of other platforms, placeholder text) and fix the listing. |

## First-run checklist for maintainers

- [ ] App IDs `com.ethosprotocol` and `com.ethosprotocol.TTLWidget` exist, with the capabilities above.
- [ ] App record for `com.ethosprotocol` exists in App Store Connect; first-version listing and App Privacy filled in.
- [ ] Private match repo created; `fastlane match appstore` run once from a Mac.
- [ ] Secrets set: `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8_BASE64`, `MATCH_GIT_URL`, `MATCH_PASSWORD`, `MATCH_GIT_BASIC_AUTHORIZATION` (https match repo). `ETHOS_CERT_PINS` has current and backup pins.
- [ ] Variable `APPLE_TEAM_IDENTIFIER` set (already required by App Links verification).
- [ ] Environment `app-store` created with required reviewers, restricted to `main` and `v*`.
- [ ] `fastlane/review_submission.yml`: every `CONFIRM` answered in a reviewed PR; `automatic_release` / `phased_release` chosen.
- [ ] Decide on the iPad orientation question (ITMS-90474 risk) before the first upload.
- [ ] **Dry run:** Actions > iOS App Store Release > Run workflow on `main` (defaults). Expect a green unsigned build.
- [ ] **TestFlight:** tag `v1.0.0` with a `fastlane/release_notes/1.0.0/en-US.txt` override (or run with `dry_run` off). Check the build and notes in TestFlight.
- [ ] **Review:** once screenshots are committed and the answers are confirmed, run with `dry_run` off and `submit_for_review` on, and approve the deployment.

## Follow-ups

- Android / Google Play automation (fastlane `supply` or Gradle Play Publisher), mirroring this flow.
- Screenshot generation (`fastlane snapshot` driven by the existing XCUITest suite).
- Create a GitHub Release from the tag with the generated notes. That would also feed
  `release-notes-parity-check.yml`, which runs on published releases (needs `contents: write`).
