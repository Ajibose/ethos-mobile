# ADR-0002: WidgetKit for iOS Widgets

## Status

Accepted

## Context

The Ethos-Protocol iOS app needs a home screen widget that displays vault TTL (time-to-live) information and allows quick check-ins without opening the app. The widget must:

- Display the most urgent vault's TTL countdown
- Allow per-vault selection via widget configuration
- Support a quick check-in action from the widget
- Operate within WidgetKit's refresh budget constraints
- Share data and API access with the main app

## Decision

Use Apple's WidgetKit framework with the following design:

1. **AppIntentConfiguration**: Each widget instance stores its own vault selection via `VaultSelectionIntent`, with no extra persistence code required.
2. **Urgency-scaled refresh intervals**: The requested next-reload interval scales from 15 minutes (comfortable TTL) down to 2 minutes (under 30 minutes from expiry), concentrating the daily refresh budget on genuinely urgent windows.
3. **Shared API client**: The widget uses the same `APIClient` instance as the main app, compiled as a separate SPM module that depends on the EthosProtocol library product.
4. **Quick check-in via AppIntent**: A `QuickCheckInIntent` performs the check-in through the shared API client, with the vault ID passed as a parameter.
5. **Fallback to most-urgent vault**: When no vault is selected (empty vaultID), the widget automatically displays the most urgent vault.

## Consequences

### Positive

- Users can view vault TTL and check in without opening the app
- Urgency-scaled refresh concentrates budget on critical windows
- Per-vault selection is handled by the framework with no extra code
- Shared API client ensures consistent behavior between app and widget

### Negative

- WidgetKit's refresh budget (40-70 reloads/day) limits how often the widget can update
- The widget runs in a separate process with its own memory and lifecycle
- SPM module boundary requires explicit `public` access for shared types

### Neutral

- The widget extension has its own `Info.plist` with a separate `API_BASE_URL`
- Widget refresh is a request, not a guarantee — WidgetKit may coalesce or delay reloads

## Alternatives Considered

| Alternative | Why Not Chosen |
|-------------|---------------|
| Static widget (no refresh) | Cannot show live TTL countdown; poor user experience |
| Frequent periodic refresh (every minute) | Exceeds WidgetKit's daily refresh budget; reloads would be silently dropped |
| Custom URL scheme for check-in | Less integrated than AppIntent; requires app to be running or a deep-link round-trip |
| Shared UserDefaults for vault selection | Redundant with AppIntentConfiguration's built-in per-instance storage |

## References

- `ios/EthosProtocol/Sources/Widget/TTLWidget.swift` — Widget implementation
- `docs/widget-refresh-budget.md` — WidgetKit refresh budget constraints
- `ios/EthosProtocol/Package.swift` — SPM package structure
