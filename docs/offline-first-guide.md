# Offline-First Architecture Guide

This guide explains the offline-first architecture of the Ethos-Protocol mobile app. It covers the principles, sync strategy, conflict resolution, and best practices for contributors.

## Overview

The Ethos-Protocol app is designed to remain functional even when network connectivity is unreliable. Users can view vault data, perform check-ins, and queue mutations while offline. The app uses a combination of disk-backed caching, durable mutation queues, and automatic retry to provide a seamless offline experience.

## Offline-First Principles

### 1. Cache-Aside Reads

All GET responses are cached on disk with a timestamp. When the device is offline, cached data is served transparently with staleness metadata so users know how fresh their data is.

- **Cache location**: App cache directory (`EthosProtocolOfflineCache/` on iOS, `ttl_offline/` on Android)
- **Cache key**: SHA-256 hash of the request URL
- **Maximum age**: 24 hours (configurable); entries older than this are treated as absent
- **Size cap**: 20 MB total, with LRU eviction (least-recently-loaded entry evicted first)
- **Invalidation**: Cache is cleared on sign-out to prevent data leakage

### 2. Write-Through Queue for Mutations

Mutations that cannot be delivered immediately are persisted to a durable on-disk queue and retried automatically when connectivity returns.

- **iOS**: `PendingCheckInStore` — JSON file in Application Support directory
- **Android**: `PendingActionDatabase` — Room database
- **Queue size**: Maximum 50 items, oldest-first eviction
- **Retry trigger**: `BGProcessingTask` (iOS) or `PendingActionSyncWorker` (Android) when connectivity returns

### 3. Idempotency Keys

Each queued mutation carries a stable idempotency key (`X-Idempotency-Key` header) so the server can deduplicate if the same mutation is submitted multiple times (e.g., after a crash between acceptance and queue draining).

### 4. Staleness Metadata

Cached entries include a timestamp so the UI can surface "last updated N hours ago" instead of silently serving stale data.

### 5. Automatic Cache Invalidation

Cache entries older than the maximum age (24 hours) are treated as absent. The cache is also cleared on sign-out.

## Sync Strategy

### Read Sync

1. **Online**: Fetch from server, cache the response, display to user
2. **Offline**: Load from cache, display with staleness indicator
3. **Cache miss (offline)**: Show "no cached data" state

### Write Sync (Check-In Queue)

1. **Online**: Send mutation directly to server
2. **Offline**: Persist to queue with idempotency key, show "queued" state to user
3. **Connectivity restored**: Background task drains queue oldest-first
   - Success: Remove from queue
   - Network error: Leave in queue, retry on next opportunity
   - Non-retryable error (400, 404): Remove from queue (permanent rejection)
   - Vault expired (410): Remove from queue, show notification

### Background Sync Triggers

| Platform | Trigger | Mechanism |
|----------|---------|-----------|
| iOS | Connectivity restored | `BGProcessingTask` with `requiresNetworkConnectivity = true` |
| iOS | Foreground retry | `NetworkMonitor` observer calls `performSync()` |
| Android | Connectivity restored | `PendingActionSyncWorker` (WorkManager) |
| Android | Foreground retry | `NetworkMonitor` observer in ViewModel |

## Conflict Resolution

### Last-Applied-Wins

When a poll response and a push notification both update the same vault, the most recently received update overwrites the local state. No field-level merging or timestamp comparison is performed.

**Rationale**: The `Vault` model carries no per-field update timestamps, making field-level merging impossible without a schema change. Both poll and push are reads of the same server-side row, so neither is more authoritative.

**Implementation**:
- iOS: `VaultStore.applyUpdate` — merges vault in-place
- Android: `VaultViewModel.updateVaultInPlace` — merges vault in-place

### Non-Retryable Errors

The following HTTP status codes are treated as non-retryable (the mutation is removed from the queue):

| Code | Meaning | Action |
|------|---------|--------|
| 400 | Bad Request | Remove from queue (permanent rejection) |
| 404 | Not Found | Remove from queue (resource gone) |
| 410 | Gone | Remove from queue, show vault-expired notification |

All other errors (401, 429, 5xx) are retryable — the mutation stays in the queue.

## Examples

### Example 1: Offline Check-In

1. User taps "Check In" while offline
2. App persists the check-in to `PendingCheckInStore` with a unique idempotency key
3. UI shows "Check-in queued — will retry automatically"
4. When connectivity returns, `CheckInSyncTask` drains the queue
5. Server accepts the check-in, app removes it from the queue
6. UI updates with the new TTL

### Example 2: Cached Vault List While Offline

1. User opens the app while offline
2. `NetworkMonitor` reports no connectivity
3. `APIClient` serves the cached vault list from `OfflineCache`
4. UI displays vaults with a "Last updated 2 hours ago" indicator
5. User can view vault details (also from cache)
6. User cannot perform mutations (they are queued instead)

### Example 3: Conflict Between Poll and Push

1. Background poll fetches vault list, receives vault A with TTL 3600
2. Simultaneously, a WebSocket push arrives with vault A with TTL 3598
3. Both updates go through the same `applyUpdate` code path
4. Whichever is received last overwrites the in-memory vault
5. No comparison is made — the most recent update wins

## Best Practices

### For Contributors

1. **Always check `NetworkMonitor.isConnected` before making network requests** — the cache and queue layers handle the rest.
2. **Use idempotency keys for all queued mutations** — this prevents duplicate submissions after crashes.
3. **Respect the non-retryable error codes** — do not retry 400, 404, or 410 errors; they indicate permanent rejection.
4. **Test offline behavior** — use the mock `NetworkPathProvider` (iOS) or `SavedStateHandle` (Android) to simulate offline conditions in tests.
5. **Surface staleness** — when serving cached data, always show the user how old the data is.
6. **Clear cache on sign-out** — this is already implemented in `OfflineCache.clearAll()`, but ensure any new cache layers follow the same pattern.

### For API Changes

1. **New GET endpoints should be cacheable** — add them to the `OfflineCache` layer.
2. **New mutation endpoints should be queueable** — add them to the `PendingCheckInStore`/`PendingActionDatabase` layer.
3. **New error codes should be classified** — determine if they are retryable or non-retryable and document them in the sync strategy.

## Code References

| Component | iOS Source | Android Source |
|-----------|-----------|---------------|
| Network monitor | `ios/EthosProtocol/Sources/Services/OfflineSupport.swift` — `NetworkMonitor` | `android/app/src/main/java/com/ethosprotocol/api/Infrastructure.kt` — `NetworkMonitor` |
| Offline cache | `ios/EthosProtocol/Sources/Services/OfflineSupport.swift` — `OfflineCache` | `android/app/src/main/java/com/ethosprotocol/api/Infrastructure.kt` — `OfflineCache` |
| Cache telemetry | `ios/EthosProtocol/Sources/Services/OfflineSupport.swift` — `CacheTelemetry` | `android/app/src/main/java/com/ethosprotocol/api/Infrastructure.kt` — `CacheTelemetry` |
| Check-in queue | `ios/EthosProtocol/Sources/Services/PendingCheckInStore.swift` | `android/app/src/main/java/com/ethosprotocol/services/PendingActionDatabase.kt` |
| Queue draining | `ios/EthosProtocol/Sources/Services/CheckInSyncTask.swift` | `android/app/src/main/java/com/ethosprotocol/services/PendingActionSyncWorker.kt` |
| Conflict resolution | `ios/EthosProtocol/Sources/ViewModels/Stores.swift` — `VaultStore.applyUpdate` | `android/app/src/main/java/com/ethosprotocol/ui/ViewModels.kt` — `VaultViewModel.updateVaultInPlace` |
| ADR | `docs/adr/adr-0001-offline-first.md` | Same |
