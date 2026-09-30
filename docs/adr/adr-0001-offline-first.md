# ADR-0001: Offline-First Architecture

## Status

Accepted

## Context

The Ethos-Protocol mobile app manages time-sensitive vault operations (check-ins, deposits, withdrawals) that must remain functional even when network connectivity is unreliable. Users may be in areas with poor coverage, or their device may temporarily lose connection. The app needs to:

- Serve cached data for reads when offline
- Queue mutations (especially check-ins) for automatic retry when connectivity returns
- Surface staleness information so users know how fresh their data is
- Resolve conflicts when local and server state diverge

## Decision

Adopt an offline-first architecture with the following principles:

1. **Cache-aside reads**: All GET responses are cached on disk with a timestamp. When offline, cached data is served transparently with staleness metadata.
2. **Write-through queue for mutations**: Mutations that cannot be delivered immediately are persisted to a durable on-disk queue and retried automatically when connectivity returns.
3. **Last-applied-wins conflict resolution**: When a poll response and a push notification both update the same vault, the most recently received update overwrites the local state. No field-level merging or timestamp comparison is performed.
4. **Idempotency keys for queued mutations**: Each queued mutation carries a stable idempotency key so the server can deduplicate if the same mutation is submitted multiple times (e.g., after a crash between acceptance and queue draining).
5. **Cache invalidation on sign-out**: All cached data is cleared when a user signs out to prevent data leakage to the next user on the same device.

## Consequences

### Positive

- Users can view vault data and perform check-ins even without connectivity
- Automatic retry reduces the need for manual intervention
- Idempotency keys prevent duplicate mutations
- Staleness metadata keeps users informed about data freshness

### Negative

- Cached data may be stale; users must be informed of staleness
- The write-through queue adds complexity to the mutation path
- Last-applied-wins means the most recent update may not always be the most "correct" one (e.g., a slow poll response arriving after a push)

### Neutral

- The cache has a maximum age (24 hours default) after which entries are treated as absent
- The queue has a maximum size (50 items) with oldest-first eviction

## Alternatives Considered

| Alternative | Why Not Chosen |
|-------------|---------------|
| Online-only (fail on no connectivity) | Poor user experience in areas with unreliable coverage; check-ins would be lost |
| Optimistic UI with rollback | Complex to implement correctly for queue-based mutations; rollback semantics are unclear for financial operations |
| Field-level conflict resolution | The Vault model carries no per-field update timestamps, making field-level merging impossible without a schema change |
| In-memory only cache | Data would be lost on app restart; disk-backed cache is necessary for reliability |

## References

- `ios/EthosProtocol/Sources/Services/OfflineSupport.swift` — NetworkMonitor, OfflineCache, CacheTelemetry
- `ios/EthosProtocol/Sources/Services/PendingCheckInStore.swift` — Disk-backed check-in queue
- `ios/EthosProtocol/Sources/Services/CheckInSyncTask.swift` — Background queue draining
- `android/app/src/main/java/com/ethosprotocol/api/Infrastructure.kt` — Android NetworkMonitor, OfflineCache
- `android/app/src/main/java/com/ethosprotocol/services/PendingActionDatabase.kt` — Room-backed mutation queue
- `android/app/src/main/java/com/ethosprotocol/services/PendingActionSyncWorker.kt` — WorkManager queue draining
- `shared/api-contract.md` — "Reconciling a poll/push disagreement" section
- `docs/offline-first-guide.md` — Full offline-first architecture guide
