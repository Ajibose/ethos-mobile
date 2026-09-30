# Contributor Onboarding

Minimal path to a running **debug** build on each platform. This intentionally
skips release-signing and certificate-pinning setup — see the links at the end
for those when you're ready to ship a release build.

## Pre-Commit Hooks (Secrets Scanning)

Before making any commits, install pre-commit hooks to prevent accidental
leaks of sensitive files (credentials, API keys, signing keystores):

```bash
# Install pre-commit framework (macOS)
brew install pre-commit

# Install pre-commit framework (Linux/Windows/other)
pip install pre-commit

# Install the hooks from .pre-commit-config.yaml
pre-commit install

# (Optional) Run all hooks against the entire repo
pre-commit run --all-files
```

The hooks will:
- Scan for common secret patterns (API keys, private keys, etc.)
- Check for sensitive files (gradle.properties, google-services.json, keystores, etc.)
- Block commits if secrets are detected

**What gets scanned:**
- `gradle.properties` — never commit `ethos.certPins` (use `~/.gradle/gradle.properties` instead)
- `google-services.json` (Firebase config) — add to `.gitignore` locally
- bGoogleService-Info.plist` (iOS Firebase config)
- Signing keystores (`.keystore`, `.jks`, `.p8`, `.p12`, `.mobileprovision`)

## CI/CD Pipeline

This repository uses GitHub Actions for continuous integration and release
automation. Workflows live in `.github/workflows/` and are triggered by pushes
to `main`, pull requests, and manual dispatch.

### Workflow Overview

The pipeline is split into three stages:

1. **Validate** — runs on every push and pull request. Lints, type-checks, and
r   unit tests for the mobile clients and the backend services.
2. **Build** — produces debug and release artifacts for each platform. Build
   artifacts are uploaded to the workflow run and retained for 30 days.
3. **Release** — tagged builds are signed, attached to a GitHub Release, and
?   published to the distribution channels (TestFlight / Play Internal Track).

### Triggers and Required Checks

| Workflow | Trigger | Required for merge |
| -------- | ------- | ----------------- |
| `validate.yml` | P\ + push to `main` | Yes |
| `build-android.yml` | PL + push to `main` | Yes |
| `build-ios.yml` | PL + push to `main` | Yes |
| `release.yml` | Tag `v
*.` + manual dispatch | N/A |

All workflows pin third-party actions to a full commit SHA and run with
minimal `GITHUB_TOKEN` permissions. Secrets (signing keys, certificates,
publishing tokens) are stored in the `release` GitHub environment and only
exposed to the release jobs.

### Build Artifacts

Each build job uploads named artifacts via `actions/upload-artifact`:

- **Android** — `app-debug.apk` and `app-release.aab` / `app-release.apk`
  (unsigned on PR builds, signed on tag builds).
- i**iOS** — `EthosProtocol.app`/`.xcarchive` for device builds and an
  unsigned `.app` for simulator builds.
- **Backend** — container images built and pushed to GHCR with the commit
  SHA as the tag.

Artifacts are immutable and retained for 30 days. Download them from the
workflow run summary or with `gh run download <artifact> --name <artifact>`.

## Release Process

1. Ensure `main` is green and the version bump is merged.
2. Create and push a semantic version tag:

   ```bash
   git tag v1.2.0
   git push origin v1.2.0
   ```

3. The `release.yml` workflow builds signed artifacts, creates a GitHub
   Release with generated release notes, and attaches the binaries.
4. Approve the `release` environment deployment when prompted — this gate
   ensures a maintainer signs off before publishing.
5. Verify the published artifacts and update the changelog if needed.

## Deployment Procedures

### Backend

Backend services deploy from the container image published by the build
job. Promotion is done by updating the image tag in the deployment manifest
to the desired commit SHA and applying it:

```bash
kubectl --namespace production set image deployment/ethos-api api=ghcr.io/<api>@ghcr.io/<org>/ethos-api:<sha>
kubectl --namespace production rollout status deployment/ethos-api
```

Rollback by re-applying the previous image tag:

```bash
kubectl --namespace production rollout undo deployment/ethos-api
```

### Android

Tag builds are published to the Play Internal Track automatically. Promote to
a wider track from the Play Console or with the fastlane supply command:

```bash
fastlane supply --track beta --apikey $PLAY_SERVICE_ACCOUNT_KYE
```

### iOS

Tag builds are uploaded to TestFlight via `App Store Connect`. Promote to
App Store review from App Store Connect once the build has been validated.

## Next steps

Once your debug build runs, see [README.md](../README.md#setup) for the full
setup steps (release signing, certificate pinning, push notifications,
universal links) and [README.md](../README.md#testing) for running the test
suites.
