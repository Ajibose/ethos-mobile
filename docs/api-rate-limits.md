# API Rate Limit Documentation

Rate limits protect the Ethos-Protocol API from abuse and ensure fair usage across all clients. This document describes the rate limits, quotas, and backoff strategies for each endpoint category.

## Rate Limit Headers

When a rate limit is exceeded, the server responds with HTTP `429 Too Many Requests` and includes a `Retry-After` response header indicating how many seconds to wait before retrying.

| Header | Description |
|--------|-------------|
| `Retry-After` | Seconds to wait before retrying the request |

## Endpoint Rate Limits

### Authentication Endpoints

| Endpoint | Rate Limit | Quota Window | Notes |
|----------|-----------|--------------|-------|
| `POST /auth/challenge` | 30 requests | 1 minute | Per IP address |
| `POST /auth/verify` | 30 requests | 1 minute | Per IP address |
| `POST /auth/register` | 10 requests | 1 minute | Per IP address |
| `POST /auth/refresh` | 60 requests | 1 minute | Per authenticated user |
| `POST /auth/recover/link` | 5 requests | 1 minute | Per IP address |
| `GET /auth/sessions` | 60 requests | 1 minute | Per authenticated user |
| `DELETE /auth/sessions/{id}` | 30 requests | 1 minute | Per authenticated user |
| `DELETE /auth/sessions` | 10 requests | 1 minute | Per authenticated user |

### Vault Endpoints

| Endpoint | Rate Limit | Quota Window | Notes |
|----------|-----------|--------------|-------|
| `GET /vaults` | 120 requests | 1 minute | Per authenticated user |
| `POST /vaults` | 30 requests | 1 minute | Per authenticated user |
| `GET /vaults/{id}` | 120 requests | 1 minute | Per authenticated user |
| `POST /vaults/{id}/checkin` | 60 requests | 1 minute | Per authenticated user |
| `POST /vaults/{id}/deposit` | 30 requests | 1 minute | Per authenticated user |
| `POST /vaults/{id}/withdraw` | 30 requests | 1 minute | Per authenticated user |
| `POST /vaults/{id}/beneficiary` | 30 requests | 1 minute | Per authenticated user |
| `GET /vaults/{id}/ttl` | 120 requests | 1 minute | Per authenticated user |
| `POST /vaults/{id}/accept` | 10 requests | 1 minute | Per vault (one-time token) |

### Notification Endpoints

| Endpoint | Rate Limit | Quota Window | Notes |
|----------|-----------|--------------|-------|
| `POST /notifications/register` | 30 requests | 1 minute | Per authenticated user |
| `DELETE /notifications/register` | 30 requests | 1 minute | Per authenticated user |

### WebSocket

| Endpoint | Rate Limit | Quota Window | Notes |
|----------|-----------|--------------|-------|
| `wss://api.ethos-protocol.app/v1/ws` | 10 connections | 1 minute | Per authenticated user |

## Client-Side Rate Limiters

The mobile apps implement additional client-side rate limiting as a complement to server-side protection.

### OTP Verification Rate Limiter

Two-factor authentication (OTP) verification attempts are rate-limited on the client with an escalating cooldown schedule:

| Cumulative Failures | Cooldown |
|---------------------|----------|
| 1–2 | None (grace period) |
| 3 | 30 seconds |
| 4 | 60 seconds |
| 5+ | 120 seconds (capped) |

A successful verification resets all counters. State persists across app restarts.

### Deep Link Rate Limiter

Deep-link-triggered API calls are throttled per vault ID with a 2-second minimum interval between consecutive calls. This prevents abuse from malicious or repeatedly-opened deep links.

## Backoff Strategies

### HTTP Request Backoff

When a request fails with a retryable error (429, 5xx, or transient network error), the client should use exponential backoff with jitter:

```
base_delay = 1 second
max_delay  = 30 seconds
capped     = min(max_delay, base_delay * 2^attempt)
actual     = random_uniform(0, capped)
```

- `attempt` is 0-based and resets to 0 on the first message successfully received from a new connection.
- Full jitter (not additive) spreads retries across the full `[0, capped)` window instead of clustering near `capped`.

### WebSocket Reconnect Backoff

WebSocket reconnects use the same exponential backoff with full jitter formula. On a 4401 close code, do not reconnect — re-authenticate first.

### Retry-After Header

When a 429 response includes a `Retry-After` header, the client must wait at least the indicated number of seconds before retrying. This takes precedence over the exponential backoff calculation.

## Rate Limit Status

For a visual overview of rate limit quotas and current usage, see the [Rate Limit Status Page](rate-limit-status.html).

## Code References

| Component | iOS Source | Android Source |
|-----------|-----------|---------------|
| 429 handling | `ios/EthosProtocol/Sources/Services/APIClient.swift` — falls through to `APIError.serverError` | `android/app/src/main/java/com/ethosprotocol/api/ApiClient.kt` — explicit 429 case |
| Retry-After header | Not currently read | `android/app/src/main/java/com/ethosprotocol/api/ApiClient.kt` — `retryAfterMessage()` |
| OTP rate limiter | `ios/EthosProtocol/Sources/Services/OTPRateLimiter.swift` | `android/app/src/main/java/com/ethosprotocol/ui/ViewModels.kt` — `TwoFactorViewModel` |
| Deep link rate limiter | — | `android/app/src/main/java/com/ethosprotocol/services/DeepLinkRateLimiter.kt` |
| WebSocket backoff | `ios/EthosProtocol/Sources/Services/VaultEventSocket.swift` — `ReconnectBackoff` | `android/app/src/main/java/com/ethosprotocol/services/VaultEventSocket.kt` — `ReconnectBackoff` |
| HTTP retry | `ios/EthosProtocol/Sources/Services/APIClient.swift` — `withRetry()` | `android/app/src/main/java/com/ethosprotocol/api/ApiClient.kt` — `withRetry()` |
