import WidgetKit
import SwiftUI
import AppIntents
// The SPM package (Package.swift) compiles TTLWidget as a separate module
// that depends on the EthosProtocol library product, so APIClient/Vault
// need an explicit import there. The XcodeGen-generated app-extension
// target (project.yml) instead compiles Models/APIClient directly into
// this same module (no EthosProtocol product exists in that project), so
// the import must be skipped there — canImport(EthosProtocol) is false in
// that build and this block compiles out entirely.
#if canImport(EthosProtocol)
import EthosProtocol
#endif

// MARK: - Quick Check-In Intent (#372)

struct QuickCheckInIntent: AppIntent {
    static let title: LocalizedStringResource = "Quick Check-In"
    static let description = IntentDescription("Quickly check in a vault from the lock screen without opening the app.")

    @Parameter(title: "Vault ID") var vaultID: String

    func perform() async throws -> some IntentResult {
        // Perform the check-in via the API
        do {
            try await APIClient.shared.checkIn(vaultID: vaultID, idempotencyKey: nil)
            return .result(value: vaultID)
        } catch {
            throw error
        }
    }
}

// MARK: - Vault Selection Intent (#245 / #246 / #431)
//
// Each widget instance stores its own VaultSelectionIntent automatically via
// AppIntentConfiguration — per-instance config is handled by the framework with
// no extra persistence code required on our side.
//
// #431: Two new parameters are added:
//   - refreshInterval: how often (in minutes) the timeline should poll for new data.
//     Options: 15 min (default), 30 min, 60 min.  Maps to WidgetKit's .after() policy.
//   - colorScheme: tint colour preference for the widget accent.
//     Options: system default (.auto), blue, cyan, or orange.
//
// SNAPSHOT TEST NOTE (#246):
// Per-instance widget configuration is verified through AppIntentConfiguration's
// built-in intent storage. Each widget instance independently stores its
// VaultSelectionIntent (including the chosen vaultID). When vaultID is empty,
// the widget falls back to the most-urgent vault (urgency selection). This
// means snapshot tests should cover three scenarios:
//   1. No intent set (empty vaultID) → most-urgent vault shown
//   2. Intent set to a specific vault ID that exists → that vault shown
//   3. Intent set to a vault ID that no longer exists → fallback to most-urgent

/// Refresh interval options exposed in the widget configuration UI (#431).
enum WidgetRefreshInterval: Int, AppEnum {
    case fifteenMinutes = 15
    case thirtyMinutes  = 30
    case sixtyMinutes   = 60

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Refresh Interval"
    static let caseDisplayRepresentations: [WidgetRefreshInterval: DisplayRepresentation] = [
        .fifteenMinutes: "Every 15 minutes",
        .thirtyMinutes:  "Every 30 minutes",
        .sixtyMinutes:   "Every 60 minutes",
    ]

    /// Returns the interval in minutes to use for WidgetKit's `.after` policy.
    /// Urgency-based overrides in `TTLTimelineProvider` may use a *shorter* interval
    /// regardless of this preference; this value is only the user-configured ceiling.
    var minutes: Int { rawValue }
}

/// Colour scheme / accent colour preference for the widget (#431).
enum WidgetColorScheme: String, AppEnum {
    case auto   = "auto"
    case blue   = "blue"
    case cyan   = "cyan"
    case orange = "orange"

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Color Scheme"
    static let caseDisplayRepresentations: [WidgetColorScheme: DisplayRepresentation] = [
        .auto:   "System Default",
        .blue:   "Blue",
        .cyan:   "Cyan",
        .orange: "Orange",
    ]

    /// Resolves the configured colour for use in SwiftUI views.
    /// `.auto` defers to the dark/light adaptive logic already in `TTLWidgetView`.
    func accentColor(isDark: Bool) -> Color {
        switch self {
        case .auto:   return isDark ? .cyan : .blue
        case .blue:   return .blue
        case .cyan:   return .cyan
        case .orange: return .orange
        }
    }
}

struct VaultSelectionIntent: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "Vault Widget Settings"

    // #245 / #246: Primary vault selection (empty → urgency fallback).
    @Parameter(title: "Vault ID", default: "") var vaultID: String

    // #431: Refresh interval preference.
    @Parameter(title: "Refresh Interval", default: .fifteenMinutes)
    var refreshInterval: WidgetRefreshInterval

    // #431: Colour scheme preference.
    @Parameter(title: "Color Scheme", default: .auto)
    var colorScheme: WidgetColorScheme
}

// MARK: - Timeline Entry

/// A single vault row shown in the multi-vault large view (#433).
struct VaultRow: Identifiable {
    let id: String          // vault ID (used as SwiftUI list identity)
    let name: String
    let ttlRemaining: UInt64?
    let isExpiringSoon: Bool
}

struct VaultEntry: TimelineEntry {
    let date: Date
    // Primary vault (shown in all sizes).
    let vaultID: String
    let vaultName: String
    let ttlRemaining: UInt64?
    let isExpiringSoon: Bool
    let balance: String
    let beneficiary: String
    // #431: User-configured colour scheme (drives accent colour in all views).
    var colorScheme: WidgetColorScheme = .auto
    // #433: Additional vault rows for the systemLarge multi-vault view (up to 3 total).
    // When empty the large view falls back to the single-vault layout.
    var additionalVaults: [VaultRow] = []
}

// MARK: - Timeline Provider

struct TTLTimelineProvider: AppIntentTimelineProvider {
    typealias Intent = VaultSelectionIntent

    func placeholder(in context: Context) -> VaultEntry {
        VaultEntry(
            date: .now,
            vaultID: "vault-placeholder",
            vaultName: LocalizedStrings.myVault,
            ttlRemaining: 86_400,
            isExpiringSoon: false,
            balance: "1.0000000 XLM",
            beneficiary: "GXYZ…"
        )
    }

    func snapshot(for intent: VaultSelectionIntent, in context: Context) async -> VaultEntry {
        VaultEntry(
            date: .now,
            vaultID: "vault-placeholder",
            vaultName: LocalizedStrings.myVault,
            ttlRemaining: 86_400,
            isExpiringSoon: false,
            balance: "1.0000000 XLM",
            beneficiary: "GXYZ…",
            colorScheme: intent.colorScheme
        )
    }

    func timeline(for intent: VaultSelectionIntent, in context: Context) async -> Timeline<VaultEntry> {
        // #432 / #434: Skip the network fetch when data is still fresh or a reload is
        // cooling down. The existing TimelineEntry already reflects the latest vault state;
        // requesting another full timeline reload would waste the WidgetKit daily budget
        // (40–70 reloads/day — see docs/widget-refresh-budget.md) for no visible change.
        // `shouldRefreshOnLaunch` reuses the same combined staleness + cooldown guard used
        // by the app-launch path in EthosProtocolApp.
        let smartRefresh = WidgetSmartRefresh.shared
        let isStillFresh = smartRefresh.isDataFresh() || smartRefresh.isCoolingDown()

        let entry: VaultEntry
        if isStillFresh {
            // Return a minimal placeholder entry so WidgetKit re-schedules the next
            // natural tick without burning a network request.
            entry = VaultEntry(
                date: .now,
                vaultID: "",
                vaultName: LocalizedStrings.unavailable,
                ttlRemaining: nil,
                isExpiringSoon: false,
                balance: "—",
                beneficiary: "—",
                colorScheme: intent.colorScheme
            )
        } else {
            do {
                let vaults = try await APIClient.shared.listAllVaults()
                let activeVaults = vaults.filter { $0.status == .active }

                // If the intent specifies a vault ID, try to find that vault.
                // Otherwise fall back to the most-urgent vault (lowest ttlRemaining).
                let selected: Vault?
                if !intent.vaultID.isEmpty {
                    selected = activeVaults.first(where: { $0.id == intent.vaultID })
                        ?? activeVaults.min(by: { ($0.ttlRemaining ?? UInt64.max) < ($1.ttlRemaining ?? UInt64.max) })
                } else {
                    selected = activeVaults.min(by: { ($0.ttlRemaining ?? UInt64.max) < ($1.ttlRemaining ?? UInt64.max) })
                }

                // #433: Build additional vault rows for the systemLarge multi-vault view.
                // Sort all active vaults by urgency; exclude the primary so there are no
                // duplicates; take up to 2 more (3 total including the primary row).
                let otherVaults = activeVaults
                    .filter { $0.id != selected?.id }
                    .sorted { ($0.ttlRemaining ?? UInt64.max) < ($1.ttlRemaining ?? UInt64.max) }
                    .prefix(2)
                let additionalRows = otherVaults.map { v in
                    VaultRow(
                        id: v.id,
                        name: String(v.id.prefix(12)) + "…",
                        ttlRemaining: v.ttlRemaining,
                        isExpiringSoon: v.isExpiringSoon
                    )
                }

                entry = VaultEntry(
                    date: .now,
                    vaultID: selected?.id ?? "",
                    vaultName: selected.map { String($0.id.prefix(12)) + "…" } ?? LocalizedStrings.noActiveVault,
                    ttlRemaining: selected?.ttlRemaining,
                    isExpiringSoon: selected?.isExpiringSoon ?? false,
                    balance: selected.map { formatBalance($0.balance) } ?? "—",
                    beneficiary: selected.map { String($0.beneficiary.prefix(12)) + "…" } ?? "—",
                    colorScheme: intent.colorScheme,
                    additionalVaults: additionalRows
                )
                // Record the successful data fetch so the app-side staleness gate reflects it.
                smartRefresh.recordVaultDataUpdate()
                if let minInterval = activeVaults.compactMap({ $0.ttlRemaining }).min() {
                    // ttlRemaining is the time remaining (not the check-in interval), but for
                    // infrequent-user detection we track checkInInterval where available;
                    // fall back to ttlRemaining as a conservative proxy.
                    smartRefresh.updateCheckInInterval(Double(minInterval))
                }
            } catch {
                entry = VaultEntry(
                    date: .now,
                    vaultID: "",
                    vaultName: LocalizedStrings.unavailable,
                    ttlRemaining: nil,
                    isExpiringSoon: false,
                    balance: "—",
                    beneficiary: "—",
                    colorScheme: intent.colorScheme
                )
            }
        }

        // #431: Respect the user's configured refresh interval as a ceiling.
        // Urgency-based shortening still applies when TTL is critically low —
        // we use the *minimum* of the configured ceiling and the urgency interval.
        let urgencyMinutes = computeNextUpdateInterval(ttlRemaining: entry.ttlRemaining)
        let configuredCeiling = intent.refreshInterval.minutes
        let nextUpdateMinutes = min(urgencyMinutes, configuredCeiling)
        let nextUpdate = Calendar.current.date(byAdding: .minute, value: nextUpdateMinutes, to: .now)!
        return Timeline(entries: [entry], policy: .after(nextUpdate))
    }

    // Compute the next-update interval (in minutes) based on TTL urgency.
    // Returns values between 1 and 15, scaling down as ttlRemaining approaches zero.
    func computeNextUpdateInterval(ttlRemaining: UInt64?) -> Int {
        guard let ttl = ttlRemaining else { return 15 }

        // Scale based on time remaining until expiry
        switch ttl {
        case 21_600...: return 15  // >= 6 hours: refresh every 15 min
        case 3_600..<21_600: return 10  // 1-6 hours: refresh every 10 min
        case 1_800..<3_600: return 5  // 30 min-1 hour: refresh every 5 min
        case 0..<1_800: return 2  // < 30 min: refresh every 2 min
        default: return 15
        }
    }

    private func formatBalance(_ stroops: UInt64) -> String {
        let xlm = Double(stroops) / 10_000_000.0
        return String(format: "%.7f XLM", xlm)
    }
}

// MARK: - Widget View

struct TTLWidgetView: View {
    let entry: VaultEntry
    @Environment(\.widgetFamily) private var family
    // #439: Read the current colour scheme so views can adapt tint colours
    // without needing separate dark/light layouts.
    @Environment(\.colorScheme) private var colorScheme

    // #431: Accent colour respects the user's configured colour scheme preference.
    // Falls back to the dark/light adaptive default when set to .auto.
    private var widgetAccentColor: Color {
        entry.colorScheme.accentColor(isDark: colorScheme == .dark)
    }

    var body: some View {
        switch family {
        case .systemSmall:
            smallView
        case .systemMedium:
            mediumView
        case .systemLarge:
            largeView
        case .accessoryRectangular, .accessoryCircular:
            compactView
        default:
            smallView
        }
    }

    // MARK: .systemSmall — vault name + TTL countdown only
    private var smallView: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(LocalizedStrings.widgetTitle, systemImage: "lock.shield.fill")
                .font(.caption2.bold())
                .foregroundStyle(widgetAccentColor)
                .accessibilityHidden(true)
            Text(entry.vaultName)
                .font(.headline)
                .lineLimit(1)
                .accessibilityLabel("Vault name")
                .accessibilityValue(entry.vaultName)
            if let ttl = entry.ttlRemaining {
                Text(formatDuration(ttl))
                    .font(.subheadline)
                    .foregroundStyle(entry.isExpiringSoon ? .orange : .secondary)
                    .accessibilityLabel("Time remaining")
                    .accessibilityValue(formatDuration(ttl))
            } else {
                Text("—").font(.subheadline).foregroundStyle(.secondary)
                    .accessibilityLabel("Time remaining")
                    .accessibilityValue("Unknown")
            }
            if entry.isExpiringSoon {
                Label(LocalizedStrings.expiringsoon, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption2)
                    .foregroundStyle(.orange)
                    .accessibilityLabel("Warning")
                    .accessibilityValue("Vault expiring soon")
            }
        }
        .padding()
        // .containerBackground(.regularMaterial, for: .widget) automatically provides an
        // adaptive background that matches the system appearance in both light and dark
        // mode — no manual colour switching is needed for the widget background. (#439)
        .containerBackground(.regularMaterial, for: .widget)
        .widgetURL(URL(string: "ethosprotocol://vault/\(entry.vaultID)/view-details"))
        .accessibilityElement(children: .combine)
    }

    // MARK: .systemMedium — TTL + balance + quick check-in
    private var mediumView: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(LocalizedStrings.widgetTitle, systemImage: "lock.shield.fill")
                .font(.caption2.bold())
                .foregroundStyle(widgetAccentColor)
                .accessibilityHidden(true)
            Text(entry.vaultName)
                .font(.headline)
                .lineLimit(1)
                .accessibilityLabel("Vault name")
                .accessibilityValue(entry.vaultName)
            if let ttl = entry.ttlRemaining {
                Text(formatDuration(ttl))
                    .font(.subheadline)
                    .foregroundStyle(entry.isExpiringSoon ? .orange : .secondary)
                    .accessibilityLabel("Time remaining")
                    .accessibilityValue(formatDuration(ttl))
            } else {
                Text("—").font(.subheadline).foregroundStyle(.secondary)
                    .accessibilityLabel("Time remaining")
                    .accessibilityValue("Unknown")
            }
            HStack {
                Label(entry.balance, systemImage: "dollarsign.circle")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .accessibilityLabel("Balance")
                    .accessibilityValue(entry.balance)
            }

            if entry.isExpiringSoon {
                Label(LocalizedStrings.expiringsoon, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption2)
                    .foregroundStyle(.orange)
                    .accessibilityLabel("Warning")
                    .accessibilityValue("Vault expiring soon")
            }
        }
        .padding()
        .containerBackground(.regularMaterial, for: .widget)
        .widgetURL(URL(string: "ethosprotocol://vault/\(entry.vaultID)/view-details"))
        .accessibilityElement(children: .combine)
    }

    // MARK: .systemLarge — primary vault full detail + up to 2 additional vaults (#433)
    private var largeView: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(LocalizedStrings.widgetTitle, systemImage: "lock.shield.fill")
                .font(.caption2.bold())
                .foregroundStyle(widgetAccentColor)
                .accessibilityHidden(true)

            // Primary vault row
            primaryVaultSection

            // #433: Additional vault rows (shown only when there are more active vaults).
            if !entry.additionalVaults.isEmpty {
                Divider().accessibilityHidden(true)
                Text("Other Vaults")
                    .font(.caption2.bold())
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
                ForEach(entry.additionalVaults) { row in
                    additionalVaultRow(row)
                }
            }

            Spacer().accessibilityHidden(true)
        }
        .padding()
        .containerBackground(.regularMaterial, for: .widget)
        .widgetURL(URL(string: "ethosprotocol://vault/\(entry.vaultID)/view-details"))
        .accessibilityElement(children: .combine)
    }

    // Primary vault detail block (used by the large view).
    private var primaryVaultSection: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(entry.vaultName)
                .font(.title3.bold())
                .lineLimit(1)
                .accessibilityLabel("Vault name")
                .accessibilityValue(entry.vaultName)
            if let ttl = entry.ttlRemaining {
                LabeledContent(LocalizedStrings.ttlLabel) {
                    Text(formatDuration(ttl))
                        .foregroundStyle(entry.isExpiringSoon ? .orange : .primary)
                }
                .font(.subheadline)
                .accessibilityElement(children: .combine)
                .accessibilityLabel("Time remaining")
                .accessibilityValue(formatDuration(ttl))
            } else {
                LabeledContent(LocalizedStrings.ttlLabel) {
                    Text("—").foregroundStyle(.secondary)
                }
                .font(.subheadline)
                .accessibilityElement(children: .combine)
                .accessibilityLabel("Time remaining")
                .accessibilityValue("Unknown")
            }
            LabeledContent(LocalizedStrings.balanceLabel) {
                Text(entry.balance).foregroundStyle(.secondary)
            }
            .font(.subheadline)
            .accessibilityElement(children: .combine)
            .accessibilityLabel("Balance")
            .accessibilityValue(entry.balance)
            LabeledContent("Beneficiary") {
                Text(entry.beneficiary).foregroundStyle(.secondary).lineLimit(1)
            }
            .font(.subheadline)
            .accessibilityElement(children: .combine)
            .accessibilityLabel("Beneficiary")
            .accessibilityValue(entry.beneficiary)
            if entry.isExpiringSoon {
                Label(LocalizedStrings.expiringsoon, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .padding(.top, 2)
                    .accessibilityLabel("Warning: vault expiring soon")
            }
        }
    }

    // A compact row for one additional vault in the large multi-vault view (#433).
    private func additionalVaultRow(_ row: VaultRow) -> some View {
        HStack {
            Text(row.name)
                .font(.caption)
                .lineLimit(1)
                .foregroundStyle(.primary)
            Spacer()
            if let ttl = row.ttlRemaining {
                Text(formatDuration(ttl))
                    .font(.caption)
                    .foregroundStyle(row.isExpiringSoon ? .orange : .secondary)
            } else {
                Text("—").font(.caption).foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(row.name), time remaining \(row.ttlRemaining.map { formatDuration($0) } ?? "unknown")")
    }

    // MARK: .accessoryRectangular / .accessoryCircular — compact lock-screen view with quick action
    private var compactView: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(LocalizedStrings.widgetTitle, systemImage: "lock.shield.fill")
                .font(.caption2.bold())
                .foregroundStyle(widgetAccentColor)
                .accessibilityHidden(true)
            Text(entry.vaultName)
                .font(.headline)
                .lineLimit(1)
                .accessibilityLabel("Vault name")
                .accessibilityValue(entry.vaultName)
            if let ttl = entry.ttlRemaining {
                Text(formatDuration(ttl))
                    .font(.subheadline)
                    .foregroundStyle(entry.isExpiringSoon ? .orange : .secondary)
                    .accessibilityLabel("Time remaining")
                    .accessibilityValue(formatDuration(ttl))
            } else {
                Text("—").font(.subheadline).foregroundStyle(.secondary)
                    .accessibilityLabel("Time remaining")
                    .accessibilityValue("Unknown")
            }
            if entry.isExpiringSoon {
                Label(LocalizedStrings.expiringsoon, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption2)
                    .foregroundStyle(.orange)
                    .accessibilityLabel("Warning")
                    .accessibilityValue("Vault expiring soon")
            }
            .buttonStyle(.bordered)
            .tint(widgetAccentColor)
            .padding(.top, 4)
        }
        .padding()
        // .containerBackground(.regularMaterial, for: .widget) automatically provides an
        // adaptive background that matches the system appearance — light vibrancy in light
        // mode and a dark translucent surface in dark mode — so no manual colour switching
        // is needed for the widget background. (#439)
        .containerBackground(.regularMaterial, for: .widget)
        .widgetURL(URL(string: "ethosprotocol://vault/\(entry.vaultID)/view-details"))
        .accessibilityElement(children: .combine)
    }

    private func formatDuration(_ seconds: UInt64) -> String {
        DateTimeFormatter.shared.formatDurationInSeconds(seconds)
    }
}

// MARK: - Widget Definition

struct TTLWidget: Widget {
    let kind = "TTLWidget"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: kind, intent: VaultSelectionIntent.self, provider: TTLTimelineProvider()) { entry in
            TTLWidgetView(entry: entry)
        }
        .configurationDisplayName("TTL Vault Status")
        .description("Shows your vault's TTL countdown. Tap to configure which vault to display.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .accessoryRectangular, .accessoryCircular])
    }
}

// MARK: - Widget Bundle Entry Point (app extension @main)

@main
struct TTLWidgetBundle: WidgetBundle {
    var body: some Widget {
        TTLWidget()
    }
}

// MARK: - Dark Mode Previews (#439)
//
// These previews render each widget size in dark mode so you can verify that
// widgetAccentColor (.cyan), .primary/.secondary foreground styles, and the
// .containerBackground(.regularMaterial) all look correct without running on
// a physical device.

@available(iOSApplicationExtension 17.0, *)
#Preview("Small – Dark", as: .systemSmall) {
    TTLWidget()
} timeline: {
    VaultEntry(
        date: .now,
        vaultID: "vault-dark-small",
        vaultName: "Dark Vault",
        ttlRemaining: 82_800,
        isExpiringSoon: false,
        balance: "1.0000000 XLM",
        beneficiary: "GXYZ…"
    )
}

@available(iOSApplicationExtension 17.0, *)
#Preview("Medium – Dark", as: .systemMedium) {
    TTLWidget()
} timeline: {
    VaultEntry(
        date: .now,
        vaultID: "vault-dark-medium",
        vaultName: "Expiring Vault",
        ttlRemaining: 1_800,
        isExpiringSoon: true,
        balance: "2.5000000 XLM",
        beneficiary: "GABC…"
    )
}

@available(iOSApplicationExtension 17.0, *)
#Preview("Large – Dark", as: .systemLarge) {
    TTLWidget()
} timeline: {
    VaultEntry(
        date: .now,
        vaultID: "vault-dark-large",
        vaultName: "My Primary Vault",
        ttlRemaining: 3_600,
        isExpiringSoon: true,
        balance: "0.5000000 XLM",
        beneficiary: "GDEF…"
    )
}
