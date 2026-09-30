#!/usr/bin/env python3
"""
Error code lookup tool for the Ethos-Protocol API.

Usage:
    python3 scripts/lookup_error_code.py <code-or-keyword>

Examples:
    python3 scripts/lookup_error_code.py 401
    python3 scripts/lookup_error_code.py replay
    python3 scripts/lookup_error_code.py rate
    python3 scripts/lookup_error_code.py network
"""

import sys

ERROR_CODES = [
    {
        "code": "400",
        "name": "Bad Request",
        "category": "http",
        "scenarios": [
            "Replay detected (duplicate nonce)",
            "Replay detected (stale timestamp)",
            "Invalid request body",
        ],
        "recovery": "Generate a fresh X-Nonce and current X-Timestamp, then retry",
        "ios_api_error": "APIError.serverError(message)",
        "android_api_error": "ApiResult.Error(message, 400)",
    },
    {
        "code": "401",
        "name": "Unauthorized",
        "category": "http",
        "scenarios": [
            "Missing or expired JWT",
            "Invalid JWT",
            "Expired recovery token",
            "Invalid acceptance token",
        ],
        "recovery": "Sign in again; for recovery tokens, request a new code",
        "ios_api_error": "APIError.unauthorized",
        "android_api_error": "ApiResult.Error(message, 401)",
    },
    {
        "code": "404",
        "name": "Not Found",
        "category": "http",
        "scenarios": [
            "Vault not found",
            "Session not found",
        ],
        "recovery": "Verify the resource ID; it may have been deleted or already revoked",
        "ios_api_error": "APIError.notFound",
        "android_api_error": "ApiResult.Error(\"Not found\", 404)",
    },
    {
        "code": "409",
        "name": "Conflict",
        "category": "http",
        "scenarios": [
            "Replay detected (alternative to 400)",
        ],
        "recovery": "Same as 400 — generate a fresh nonce and timestamp",
        "ios_api_error": "APIError.serverError(message)",
        "android_api_error": "ApiResult.Error(message, 409)",
    },
    {
        "code": "410",
        "name": "Gone",
        "category": "http",
        "scenarios": [
            "Vault has expired",
        ],
        "recovery": "No action needed; the client deletes the queued check-in and shows a notification",
        "ios_api_error": "CheckInSyncTask deletes item, shows notification",
        "android_api_error": "PendingActionSyncWorker deletes item, shows notification",
    },
    {
        "code": "429",
        "name": "Too Many Requests",
        "category": "http",
        "scenarios": [
            "Rate limited",
        ],
        "recovery": "Respect the Retry-After response header; wait the indicated seconds before retrying",
        "ios_api_error": "Falls through to APIError.serverError (not explicitly handled)",
        "android_api_error": "ApiResult.Error(\"Rate limited. Retry after N seconds.\", 429)",
    },
    {
        "code": "5xx",
        "name": "Server Error",
        "category": "http",
        "scenarios": [
            "Internal server error",
            "Service unavailable",
        ],
        "recovery": "Retry with backoff; if the problem persists, contact support",
        "ios_api_error": "APIError.serverError(message)",
        "android_api_error": "ApiResult.Error(\"Server error N\", N)",
    },
    {
        "code": "network",
        "name": "Network Unavailable",
        "category": "client",
        "scenarios": [
            "No network connectivity",
        ],
        "recovery": "Check network settings; cached data is served for reads automatically",
        "ios_api_error": "APIError.networkUnavailable",
        "android_api_error": "ApiResult.NetworkUnavailable",
    },
    {
        "code": "decoding",
        "name": "Decoding Failed",
        "category": "client",
        "scenarios": [
            "Server response could not be parsed",
        ],
        "recovery": "Try again; if the problem continues, contact support — this has been logged",
        "ios_api_error": "APIError.decodingFailed",
        "android_api_error": "ApiErrorMapper catches and returns generic error",
    },
    {
        "code": "circuit",
        "name": "Circuit Breaker",
        "category": "client",
        "scenarios": [
            "Consecutive failures have opened the circuit breaker (Android only)",
        ],
        "recovery": "Wait for the circuit breaker to half-open, then retry",
        "ios_api_error": "N/A",
        "android_api_error": "ApiResult.Error(\"API temporarily unavailable\", 503)",
    },
    {
        "code": "otp",
        "name": "OTP Rate Limiter",
        "category": "client",
        "scenarios": [
            "Too many failed OTP verification attempts",
        ],
        "recovery": "Wait for the cooldown to expire; countdown is displayed in the UI",
        "ios_api_error": "OTPRateLimiter.isBlocked",
        "android_api_error": "TwoFactorViewModel cooldownRemainingSeconds",
    },
    {
        "code": "passkey",
        "name": "Passkey/CredentialManager Error",
        "category": "client",
        "scenarios": [
            "User cancelled biometric prompt",
            "No passkey provider available",
            "No matching credential found",
        ],
        "recovery": "Varies by error; see docs/api-error-codes.md for the full table",
        "ios_api_error": "ASAuthorizationError",
        "android_api_error": "PasskeyException",
    },
]


def lookup(query: str) -> list[dict]:
    """Find error codes matching the query (code number or keyword)."""
    query_lower = query.lower().strip()
    results = []
    for entry in ERROR_CODES:
        if query_lower in entry["code"].lower():
            results.append(entry)
        elif query_lower in entry["name"].lower():
            results.append(entry)
        elif any(query_lower in s.lower() for s in entry["scenarios"]):
            results.append(entry)
        elif query_lower in entry["recovery"].lower():
            results.append(entry)
    return results


def format_entry(entry: dict) -> str:
    """Format a single error code entry for display."""
    lines = [
        f"[{entry['code']}] {entry['name']} ({entry['category']})",
        f"  Scenarios:",
    ]
    for scenario in entry["scenarios"]:
        lines.append(f"    - {scenario}")
    lines.append(f"  Recovery: {entry['recovery']}")
    lines.append(f"  iOS: {entry['ios_api_error']}")
    lines.append(f"  Android: {entry['android_api_error']}")
    return "\n".join(lines)


def main():
    if len(sys.argv) < 2:
        print("Usage: python3 scripts/lookup_error_code.py <code-or-keyword>")
        print()
        print("Examples:")
        print("  python3 scripts/lookup_error_code.py 401")
        print("  python3 scripts/lookup_error_code.py replay")
        print("  python3 scripts/lookup_error_code.py rate")
        print("  python3 scripts/lookup_error_code.py network")
        print()
        print("Available codes:")
        for entry in ERROR_CODES:
            print(f"  {entry['code']:>6}  {entry['name']}")
        sys.exit(1)

    query = " ".join(sys.argv[1:])
    results = lookup(query)

    if not results:
        print(f"No error codes found matching '{query}'.")
        print()
        print("Available codes:")
        for entry in ERROR_CODES:
            print(f"  {entry['code']:>6}  {entry['name']}")
        sys.exit(1)

    for i, entry in enumerate(results):
        if i > 0:
            print()
        print(format_entry(entry))


if __name__ == "__main__":
    main()
