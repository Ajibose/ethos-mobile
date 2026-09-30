# Troubleshooting Guide

This guide is the first stop for diagnosing problems with the Ethos-Protocol mobile apps (iOS + Android). It covers the symptoms most likely to reach support, an ordered set of diagnostic steps, and step-by-step solution procedures. Each symptom in the [Common Issues](#common-issues) section links to a numbered procedure; the [Decision Tree](#decision-tree) routes an unfamiliar report to the right procedure in under a minute.

Use it in this order:

1. **Decision Tree** — triage the report by its headline symptom.
2. **Common Issues** — confirm the match and apply the fast fix.
3. **Diagnostic Steps** — if the fast fix does not hold, work the checklist top to bottom.
4. **Solution Procedures** — execute the full procedure for the matched scenario.

## Decision Tree

Answer the first question that matches the report and jump to the referenced procedure. Every `P<n>` has a full write-up in [Solution Procedures](#solution-procedures).

```
Report comes in
│
├─ Can't sign in / authentication error?
│   ├─ Passkey prompt never appears or fails ────────────── P1
│   ├─ "Too many attempts — wait Ns" ───────────────────── P2
│   └─ Signed in but every request returns 401 ──────────── P3
│
├─ Requests failing with an HTTP error?
│   ├─ 400 / 409 "replay_detected" ─────────────────────── P4
│   ├─ 429 Too Many Requests ───────────────────────────── P5
│   └─ 5xx / "API temporarily unavailable" ─────────────── P6
│
├─ Offline symptoms (no connection, stale data)? ────────── P7
│
├─ Connectivity lost right after a server cert change? ──── P8
│
└─ Build / CI failure?
    ├─ iOS: "unable to find Info.plist" ─────────────────── P9
    ├─ Android: staging APK points at the wrong API URL ─── P10
    └─ Staging smoke test fails ─────────────────────────── P11
```

## Common Issues

Each entry lists the symptom, the usual cause, and the fastest fix. The "Details" column points at the full procedure.

### Sign-in and authentication

| Symptom | Usual cause | Fast fix | Details |
|---------|-------------|----------|---------|
| Passkey prompt never appears | Biometrics not enrolled, or the associated domain is misconfigured | Check Face ID / Touch ID (iOS) or Google Password Manager / Credential Manager (Android) enrollment | P1 |
| iOS: "Invalid domain" during registration | Relying party ID does not match the domain or the Associated Domain entitlement is wrong | Verify `webcredentials` association; see the passkey flow guide | P1 |
| Android: `NoCredentialException` | Credential was never registered on this device | Sign in with an alternative method, then re-register | P1 |
| Android: `CreateCredentialNoCreateOptionException` | No passkey provider available on the device | Update Google Play Services or use an alternative method | P1 |
| "User cancelled" errors | User dismissed the biometric prompt | Not an error; retry is allowed | P1 |
| "Too many attempts — wait Ns" | Client-side OTP rate limiter engaged after repeated failures | Wait out the displayed cooldown (30 s → 60 s → 120 s cap) | P2 |
| Every request returns 401 | Stored JWT missing, expired, or invalid | Sign in again — the client deletes the stale token automatically | P3 |

### Request failures

| Symptom | Usual cause | Fast fix | Details |
|---------|-------------|----------|---------|
| 400 or 409 with `{"error": "replay_detected"}` | Duplicate nonce, or device clock skewed so `X-Timestamp` looks stale | Confirm the device clock, then retry with a fresh `X-Nonce` + current `X-Timestamp` | P4 |
| 429 Too Many Requests | Server rate limit hit | Respect the `Retry-After` header; wait the indicated seconds | P5 |
| Android: "API temporarily unavailable" (503) | Circuit breaker opened after consecutive failures | Wait for the breaker to half-open, then retry | P6 |
| 5xx server error | Backend fault | Retry with backoff; escalate if persistent | P6 |
| "We couldn't read the server's response" (iOS) / generic decode error (Android) | Server response failed to decode | Retry; if persistent, collect logs and escalate — already logged client-side | P6 |
| 404 on a vault | Vault deleted, or wrong ID | Verify the vault ID | — |
| 410 Gone | Vault already expired | No action needed; the client deletes the queued check-in and shows a notification | — |

### Offline and sync

| Symptom | Usual cause | Fast fix | Details |
|---------|-------------|----------|---------|
| "No internet connection" / "No network connection" | Device offline | Reads are served from cache with a staleness indicator; mutations are queued | P7 |
| Data looks stale offline | Cache entry older than the 24-hour maximum age is treated as absent | Reconnect to refresh; sign-out clears the cache | P7 |
| Check-in stuck in "queued" | No connectivity, or the queue has not been drained yet | It drains automatically on reconnect; foregrounding the app triggers a sync | P7 |
| Queued check-in disappeared | Server answered 400 / 404 / 410 (non-retryable) | 410 is expected for an expired vault; 400/404 mean the mutation was permanently rejected | P7 |

### Connectivity and certificates

| Symptom | Usual cause | Fast fix | Details |
|---------|-------------|----------|---------|
| All app versions lose connectivity immediately after a server cert change | Pin set in the shipped apps does not include the new certificate's SPKI hash | Revert the server certificate now, then follow the rotation runbook | P8 |
| CI warns "No pinned certificate hashes found" | `TLS_PUBLIC_KEY_PINS` empty in an Info.plist, or `ETHOS_CERT_PINS` unset with placeholder `DEFAULT_PINS` | Configure real pins on both platforms | P8 |
| CI warns about upcoming expiry after the cert was rotated | Pin set still contains the replaced hash | Open a PR to remove the stale pin | P8 |

### Build and CI

| Symptom | Usual cause | Fast fix | Details |
|---------|-------------|----------|---------|
| iOS build fails: "unable to find Info.plist" | `project.yml` changed after the last `xcodegen` run | Re-run `xcodegen generate` | P9 |
| Android staging APK talks to production | `STAGING_API_BASE_URL` not set at compile time | Set it and rebuild; the URL is baked into `BuildConfig` | P10 |
| Staging smoke test fails | Staging backend down, wrong env vars, or nonce-dedup window too wide | Check `STAGING_API_BASE_URL` and backend health | P11 |

## Diagnostic Steps

Work these in order — cheapest and most common first. Stop as soon as the cause is found.

1. **Capture the exact symptom.** The literal error message or HTTP status, the screen, the timestamp, the platform (iOS/Android), and the app version. Vague reports are the number-one time sink.
2. **Look up the error code.** Run the lookup tool with the code or a keyword from the message:
   ```bash
   python3 scripts/lookup_error_code.py <code-or-keyword>
   ```
   It prints the scenario table and recovery steps straight from `docs/api-error-codes.md`.
3. **Check basic connectivity.** Toggle airplane mode / Wi-Fi. Remember the app's offline behavior: GETs are served from the on-disk cache (with a "last updated" indicator), and mutations are queued — offline is *not* the same as broken.
4. **Check the device clock.** Replay protection signs each mutation with `X-Nonce` + `X-Timestamp`. A clock skewed more than the server's replay window produces `replay_detected` even on first-time requests.
5. **Reproduce on the other platform.** If the same account and operation fail on both iOS and Android, suspect the backend or the environment. If only one platform fails, suspect that platform's client state.
6. **Verify the backend.** Run the staging smoke test against a healthy staging deployment:
   ```bash
   ./scripts/smoke_test_staging.sh
   ```
   A green run means the API contract is intact and the problem is client-side or environmental.
7. **Clear client state, in increasing order of cost:**
   - Sign out and back in (clears the token and the offline cache).
   - Force-quit and relaunch (resets in-memory state; queues persist).
   - Reinstall the app (clears cache, mutation queue, and keychain — the nuclear option; warn the user first).
8. **Collect logs.** Xcode console (iOS) or Logcat filtered to `ethosprotocol` (Android). Decoding failures are logged by `DecodingFailureLogger` on iOS; include the console output with any escalation.
9. **Escalate with evidence.** Attach: exact error, timestamp, platform + app version, reproduction steps, and the output of steps 2, 6, and 8.

## Solution Procedures

### P1. Sign-in / passkey failures

1. Confirm biometrics are enrolled and enabled on the device (Face ID / Touch ID on iOS; screen lock + Credential Manager on Android).
2. If the passkey prompt never appears on iOS, verify the Associated Domain (`webcredentials`) entitlement and that the relying party ID matches the app's domain — see `docs/mobile-passkey-flow.md`.
3. On Android, `CreateCredentialNoCreateOptionException` means no passkey provider: update Google Play Services, or fall back to the alternative authentication method.
4. `NoCredentialException` means no matching credential exists on the device: sign in with the alternative method, then re-register the passkey.
5. "User cancelled" / `ASAuthorizationError.canceled` is the user dismissing the prompt — no fix needed; retry.
6. If registration succeeds but sign-in fails afterwards, the credential was registered against a different environment (e.g. staging vs production bundle IDs). Re-register within the intended environment.

### P2. "Too many attempts — wait Ns"

1. This is the client-side OTP rate limiter, not a server error. The cooldown schedule is: 1–2 failures → none, 3 → 30 s, 4 → 60 s, 5+ → 120 s (capped). It persists across app restarts.
2. Wait out the displayed countdown. Do not force-quit to reset it — that does not clear the persisted counters.
3. After a successful verification all counters reset.
4. If users hit this repeatedly, look for a systemic cause (wrong code distribution channel, OCR-unfriendly code display) rather than treating each report independently.

### P3. Signed in but every request returns 401

1. The stored JWT is missing, expired, or invalid. The client deletes the stored token automatically on 401 — sign in again.
2. If sign-in succeeds but the next request 401s again, confirm the device clock: a badly skewed clock can break token validation paths.
3. Confirm the build points at the intended environment (production vs staging bundle IDs coexist on one device).
4. If 401s persist across a fresh install, capture the failing request/response pair and escalate to the backend team — the token may be rejected server-side.

### P4. Replay detected (400/409 `replay_detected`)

1. Identify which trigger fired: a duplicate `X-Nonce` or a stale `X-Timestamp`.
2. If requests are genuinely duplicated (retry storm, queue drain race), note that every queued mutation already carries a stable `X-Idempotency-Key`; duplicate nonces across *different* mutations indicate a client bug — capture logs and open an issue.
3. If it happens on first-time requests, check the device clock (step 4 of [Diagnostic Steps](#diagnostic-steps)) and retry with a fresh nonce and current timestamp.
4. On staging, a 409 on the smoke test's check-in flow with a fresh run usually means the backend nonce-deduplication window is set too wide — see `docs/staging-environment.md`.

### P5. Rate limited (429)

1. Android surfaces the `Retry-After` header directly ("Rate limited. Retry after N seconds."). iOS currently falls through to the generic server-error path — check the raw status when triaging iOS reports.
2. Never retry immediately in a loop; that extends the limit. Wait the indicated seconds.
3. For bursts caused by background sync, note the cache and queue already batch retries; frequent 429s on a normal usage pattern should be raised against `docs/api-rate-limits.md`.

### P6. Server errors and circuit breaker

1. 5xx: retry with backoff (both clients implement `withRetry()`). Persistent 5xx is a backend incident — check the staging smoke test and escalate.
2. Android "API temporarily unavailable" (503) is the circuit breaker, not the server. It opens after consecutive failures and blocks requests until it half-opens. Wait, then retry; do not reinstall the app.
3. Decoding failures ("We couldn't read the server's response" on iOS, generic message on Android): retry once. If persistent, the server response shape changed — check `shared/api-contract.md` for the expected schema, collect logs (iOS logs via `DecodingFailureLogger`), and escalate with the raw payload if available.

### P7. Offline and sync problems

1. Confirm what the user expects: offline reads come from the disk cache (max age 24 h, 20 MB LRU-capped, cleared on sign-out). "No cached data" after more than a day offline is expected behavior, not a bug.
2. For a stuck "queued" check-in: foregrounding the app triggers a sync via the network monitor; otherwise `BGProcessingTask` (iOS) / `PendingActionSyncWorker` (Android) drains the queue when connectivity returns.
3. The queue holds at most 50 items, oldest-first eviction — a user who queued dozens of mutations offline may have lost the oldest ones by design.
4. A queued item that vanished was answered by the server: 400/404 mean permanent rejection (removed from queue); 410 means the vault expired (removed, with a notification). 401/429/5xx stay queued and retry.
5. If nothing drains despite full connectivity, capture logs and check whether the queue file/database is reachable; escalate with the logs.

### P8. Connectivity lost after a certificate change

1. **Treat as an incident.** If the server certificate was rotated without the new SPKI hash in the shipped apps' pin sets, every pinned app in the field loses connectivity immediately.
2. **Immediate mitigation:** revert the server to the previous certificate.
3. **Then** follow `docs/cert-pin-rotation-runbook.md` end to end: compute the new SPKI SHA-256, add it *alongside* the old pin on both platforms, ship the app update, rotate the server cert only after the update is live, and remove the old pin last.
4. If CI reports "No pinned certificate hashes found": `TLS_PUBLIC_KEY_PINS` is missing/empty in `ios/EthosProtocol/EthosProtocol/Info.plist` or `ios/EthosProtocol/TTLWidget/Info.plist`, or Android's `ETHOS_CERT_PINS` secret is unset while `CertificatePinning.kt` still has placeholder pins.
5. If CI warns about an expiry for a cert that was already rotated, the pin set still contains the replaced hash — open a PR to remove it.

### P9. iOS build fails: "unable to find Info.plist"

1. `project.yml` was changed after the last Xcode project generation. Regenerate:
   ```bash
   cd ios/EthosProtocol && mkdir -p Xcode && xcodegen generate --project Xcode
   ```
2. Re-run the build. If a *specific* target is still missing its Info.plist, confirm the target exists in `project.yml` and the path in the error matches.
3. CI uses the same generation step, so a clean local generation that fixes the build also fixes CI.

### P10. Android staging APK points at the wrong API URL

1. `STAGING_API_BASE_URL` is baked into `BuildConfig.API_BASE_URL` at compile time. Installing an APK does not change the URL — only rebuilding does.
2. Rebuild with the variable set:
   ```bash
   cd android
   STAGING_API_BASE_URL=https://staging-api.ethos-protocol.app/v1 ./gradlew assembleStaging
   ```
3. Locally you may instead set `ethos.stagingApiBaseUrl` in `~/.gradle/gradle.properties` (never commit that file).
4. Confirm on-device: the staging build uses bundle ID `com.ethosprotocol.staging` and coexists with the production build.

### P11. Staging smoke test fails

1. Confirm the environment variables: `STAGING_API_BASE_URL`, `STAGING_SMOKE_TOKEN`, `STAGING_SMOKE_VAULT_ID` (see `docs/staging-environment.md`).
2. Failure on flow 1 (`POST /auth/challenge`) usually means the staging backend is down or unreachable — check backend health before touching client code.
3. Failure on flow 4 (`POST /vaults/{id}/checkin`) with 409 on a fresh run points at the backend nonce-deduplication window, not the test.
4. Download the `smoke-test-logs` artifact from the workflow run for the full request/response transcript.
5. If all flows fail from a local run but CI is green (or vice versa), compare the base URL in use — local runs default via env/properties, CI uses repository secrets.

## Quick Reference

| Error / symptom | First stop | Full docs |
|-----------------|-----------|-----------|
| Any HTTP status code | `python3 scripts/lookup_error_code.py <code>` | [API error codes](api-error-codes.md) |
| Passkey / sign-in | P1, P2 | [Passkey flow](mobile-passkey-flow.md) |
| 401, 400, 404, 409, 410, 429, 5xx | P3–P6 | [API error codes](api-error-codes.md) |
| Offline, cache, queued check-ins | P7 | [Offline-first guide](offline-first-guide.md) |
| TLS pinning, cert rotation | P8 | [Cert pin rotation runbook](cert-pin-rotation-runbook.md) |
| Staging builds and smoke tests | P9–P11 | [Staging environment](staging-environment.md) |
| CI/CD pipeline questions | P9–P11 | [CI/CD pipeline](ci-cd-pipeline.md) |
| Release / App Store issues | — | [iOS App Store release](ios-app-store-release.md) |
| Rate limits | P5 | [API rate limits](api-rate-limits.md) |

## Related Documentation

- [API Error Code Reference](api-error-codes.md) — every HTTP and client-side error code with recovery steps
- [Offline-First Architecture Guide](offline-first-guide.md) — cache, queue, and sync behavior
- [Passkey Authentication Flow](mobile-passkey-flow.md) — WebAuthn/passkey registration and sign-in
- [Certificate Pin Rotation Runbook](cert-pin-rotation-runbook.md) — zero-downtime pin rotation
- [Staging Environment](staging-environment.md) — staging builds, secrets, smoke tests
- [CI/CD Pipeline](ci-cd-pipeline.md) — workflows, gates, and artifacts
- [API Rate Limits](api-rate-limits.md) — server rate-limiting behavior
- [Manual QA Checklist](manual-qa-checklist.md) — pre-release manual testing
- [Shared API Contract](../shared/api-contract.md) — request/response schemas both platforms implement
