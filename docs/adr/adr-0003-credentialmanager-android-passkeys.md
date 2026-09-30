# ADR-0003: CredentialManager for Android Passkeys

## Status

Accepted

## Context

The Ethos-Protocol Android app needs to support passkey (WebAuthn) authentication. The app must:

- Register new passkeys tied to user accounts
- Authenticate using existing passkeys
- Handle platform-specific errors (no biometrics enrolled, user cancellation, etc.)
- Work across Android versions (API 28+)
- Be testable without a real device or Robolectric

## Decision

Use Android's CredentialManager API with the following design:

1. **CredentialManagerFactory interface**: A factory interface wraps `CredentialManager.create()` so unit tests can supply a fake implementation without Robolectric or a real device.
2. **PasskeyService**: A singleton service that orchestrates the challenge/response flow: get challenge from API, call CredentialManager, extract COSE public key, send to backend.
3. **Error mapping**: Platform-specific `CreateCredentialException` and `GetCredentialException` subclasses are mapped to user-presentable messages via `PasskeyException`.
4. **Relying party ID**: `ethos-protocol.app` — matches the Apple App Site Association configuration on iOS.
5. **Registration returns token directly**: The backend returns a session token from `/auth/register`, so no separate `/auth/verify` call is needed after registration.

## Consequences

### Positive

- Native Android passkey support with biometric authentication
- Testable via factory injection without device dependencies
- Consistent error handling across platform edge cases
- Registration flow requires only one biometric prompt

### Negative

- CredentialManager requires API 28+ (Android 9+); older devices are not supported
- Platform error types are numerous and require individual mapping
- The factory pattern adds a layer of indirection compared to direct CredentialManager calls

### Neutral

- The relying party ID must match the iOS configuration for cross-platform consistency
- PasskeyService is a Hilt singleton injected into ViewModels

## Alternatives Considered

| Alternative | Why Not Chosen |
|-------------|---------------|
| Direct CredentialManager calls in ViewModels | Not testable without Robolectric; mixes platform API with UI logic |
| FIDO2 library (e.g., Yubico) | Adds a third-party dependency; CredentialManager is the platform-native solution |
| Custom WebAuthn implementation | Reinvents what CredentialManager already provides; error-prone |
| BiometricPrompt only (no passkey) | Does not provide cryptographic proof of possession; weaker security guarantee |

## References

- `android/app/src/main/java/com/ethosprotocol/services/PasskeyService.kt` — PasskeyService and CredentialManagerFactory
- `android/app/src/main/java/com/ethosprotocol/di/AppModule.kt` — Hilt binding for CredentialManagerFactory
- `docs/mobile-passkey-flow.md` — Cross-platform passkey flow documentation
- `shared/api-contract.md` — Passkey registration and verification contract
