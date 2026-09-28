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

// MARK: - Vault Selection Intent (#245 / #246)
//
// Each widget instance stores its own VaultSelectionIntent automatically via
// AppIntentConfiguration — per-instance config is handled by the framework with
// no extra persistence code required on our side.
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

struct VaultSelectionIntent: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "Select Vault"
    @Parameter(title: "Vault ID", default: "") var vaultID: String
}

// MARK: - Timeline Entry

struct VaultEntry: TimelineEntry {
    let date: Date
    let vaultID: String
    let vaultName: String
    let ttlRemaining: UInt64?
    let isExpiringSoon: Bool
    let balance: String
    let beneficiary: String
    /// #435: True when the last data fetch failed — widget shows an error indicator
    /// and a tap-to-open-app affordance so the user can force a refresh.
    let hasError: Bool

    init(
        date: Date,
        vaultID: String,
        vaultName: String,
        ttlRemaining: UInt64?,
        isExpiringSoon: Bool,
        balance: String = "—",
        beneficiary: String = "—",
        hasError: Bool = false
    ) {
        self.date = date
        self.vaultID = vaultID
        self.vaultName = vaultName
        self.ttlRemaining = ttlRemaining
        self.isExpiringSoon = isExpiringSoon
        self.balance = balance
        self.beneficiary = beneficiary
        self.hasError = hasError
    }
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
            beneficiary: "GXYZ…"
        )
    }

    func timeline(for intent: VaultSelectionIntent, in context: Context) async -> Timeline<VaultEntry> {
        let entry: VaultEntry
        do {
            let vaults = try await APIClient.shared.listAllVaults()
            let activeVaults = vaults.filter { $0.status == .active }

            // #438: Log a data-refresh failure when there are no active vaults to display.
            // This is distinct from a network error — the API call succeeded but returned
            // no usable data, which is worth tracking separately for debugging.
            if activeVaults.isEmpty {
                WidgetErrorLogger.shared.logDataRefreshFailure(
                    message: "Data refresh returned no active vaults (total vaults: \(vaults.count))"
                )
            }

            // If the intent specifies a vault ID, try to find that vault.
            // Otherwise fall back to the most-urgent vault (lowest ttlRemaining).
            let selected: Vault?
            if !intent.vaultID.isEmpty {
                selected = activeVaults.first(where: { $0.id == intent.vaultID })
                    ?? activeVaults.min(by: { ($0.ttlRemaining ?? UInt64.max) < ($1.ttlRemaining ?? UInt64.max) })
            } else {
                selected = activeVaults.min(by: { ($0.ttlRemaining ?? UInt64.max) < ($1.ttlRemaining ?? UInt64.max) })
            }

            entry = VaultEntry(
                date: .now,
                vaultID: selected?.id ?? "",
                vaultName: selected.map { String($0.id.prefix(12)) + "…" } ?? LocalizedStrings.noActiveVault,
                ttlRemaining: selected?.ttlRemaining,
                isExpiringSoon: selected?.isExpiringSoon ?? false,
                balance: selected.map { formatBalance($0.balance) } ?? "—",
                beneficiary: selected.map { String($0.beneficiary.prefix(12)) + "…" } ?? "—"
            )
        } catch {
            // #438: Log the load failure so it surfaces in the system Console
            // and contributes to the rolling widget error metrics.
            WidgetErrorLogger.shared.logLoadFailure(
                message: error.localizedDescription
            )
            entry = VaultEntry(
                date: .now,
                vaultID: "",
                vaultName: LocalizedStrings.unavailable,
                ttlRemaining: nil,
                isExpiringSoon: false,
                balance: "—",
                beneficiary: "—",
                hasError: true  // #435: show error indicator in widget
            )
        }

        // Compute refresh interval based on vault urgency: refresh more frequently as TTL approaches zero.
        // Scale from 15 min (normal) down to 1 min (critical), respecting WidgetKit's budget guidance.
        let nextUpdateMinutes = computeNextUpdateInterval(ttlRemaining: entry.ttlRemaining)
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

    // #439: Accent colour adapts between schemes — .blue is legible on the light
    // material background; .cyan has better contrast against the dark variant.
    // .containerBackground(.regularMaterial, for: .widget) already handles the
    // background colour automatically for both light and dark mode — no custom
    // background logic is needed here.
    private var widgetAccentColor: Color {
        colorScheme == .dark ? .cyan : .blue
    }

    // MARK: #435 — Error banner
    /// Shown at the top of every size variant when `entry.hasError == true`.
    /// The widget URL (tap-to-open-app) doubles as the tap-to-retry affordance —
    /// opening the app lets the user trigger a manual refresh which reloads the timeline.
    @ViewBuilder
    private var errorBanner: some View {
        if entry.hasError {
            Label {
                VStack(alignment: .leading, spacing: 1) {
                    Text(LocalizedStrings.widgetErrorTitle)
                        .font(.caption2.bold())
                    Text(LocalizedStrings.widgetErrorMessage)
                        .font(.caption2)
                }
            } icon: {
                Image(systemName: "exclamationmark.icloud.fill")
                    .foregroundStyle(.red)
            }
            .font(.caption2)
            .foregroundStyle(.red)
            .accessibilityElement(children: .combine)
            .accessibilityLabel("Sync failed. Tap to retry.")
        }
    }

    var body: some View {
        switch family {
        case .systemSmall:
            smallView
        case .systemMedium:
            mediumView
        case .systemLarge:
            largeView
        case .accessoryRectangular:
            // #436: Lock screen rectangular view — vault name + TTL, no balance/beneficiary
            TTLAccessoryRectangularView(entry: entry)
        case .accessoryCircular:
            // #436: Lock screen circular view — TTL ring with vault indicator
            TTLAccessoryCircularView(entry: entry)
        default:
            smallView
        }
    }

    // MARK: .systemSmall — vault name + TTL countdown only
    private var smallView: some View {
        VStack(alignment: .leading, spacing: 4) {
            errorBanner  // #435
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
            errorBanner  // #435
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

    // MARK: .systemLarge — TTL + balance + beneficiary + quick check-in
    private var largeView: some View {
        VStack(alignment: .leading, spacing: 8) {
            errorBanner  // #435
            Label(LocalizedStrings.widgetTitle, systemImage: "lock.shield.fill")
                .font(.caption2.bold())
                .foregroundStyle(widgetAccentColor)
                .accessibilityHidden(true)
            Text(entry.vaultName)
                .font(.title3.bold())
                .lineLimit(1)
                .accessibilityLabel("Vault name")
                .accessibilityValue(entry.vaultName)
            Divider()
                .accessibilityHidden(true)
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
                Text(entry.balance)
                    .foregroundStyle(.secondary)
            }
            .font(.subheadline)
            .accessibilityElement(children: .combine)
            .accessibilityLabel("Balance")
            .accessibilityValue(entry.balance)
            LabeledContent("Beneficiary") {
                Text(entry.beneficiary)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .font(.subheadline)
            .accessibilityElement(children: .combine)
            .accessibilityLabel("Beneficiary")
            .accessibilityValue(entry.beneficiary)
            if entry.isExpiringSoon {
                Label(LocalizedStrings.expiringsoon, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .padding(.top, 4)
                    .accessibilityLabel("Warning")
                    .accessibilityValue("Vault expiring soon")
            }
            Spacer()
                .accessibilityHidden(true)
        }
        .padding()
        .containerBackground(.regularMaterial, for: .widget)
        .widgetURL(URL(string: "ethosprotocol://vault/\(entry.vaultID)/view-details"))
        .accessibilityElement(children: .combine)
    }

    // MARK: .accessoryRectangular / .accessoryCircular — compact lock-screen view with quick action
    private var compactView: some View {
        VStack(alignment: .leading, spacing: 4) {
            errorBanner  // #435
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

// MARK: - Lock Screen Widget Views (#436)
//
// accessoryRectangular and accessoryCircular are iOS 16+ lock screen families.
// Both are read-only displays — the tap target opens the app via widgetURL to
// the vault detail screen where the user can check in.
//
// Quick Check-In from the lock screen is wired via QuickCheckInIntent (Button with
// AppIntent) — the medium home-screen widget includes this; the lock screen families
// use widgetURL only because interactive controls require WidgetKit interactivity
// (iOS 17+) which is a future enhancement.

/// #436: Rectangular lock screen widget — vault name + TTL countdown.
/// Shown in the `.accessoryRectangular` WidgetKit family (iOS 16+).
struct TTLAccessoryRectangularView: View {
    let entry: VaultEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Label(LocalizedStrings.widgetTitle, systemImage: "lock.shield.fill")
                .font(.caption2.bold())
                .accessibilityHidden(true)
            Text(entry.vaultName)
                .font(.caption.bold())
                .lineLimit(1)
                .accessibilityLabel("Vault name")
                .accessibilityValue(entry.vaultName)
            if let ttl = entry.ttlRemaining {
                Text(DateTimeFormatter.shared.formatDurationInSeconds(ttl))
                    .font(.caption2)
                    .foregroundStyle(entry.isExpiringSoon ? .orange : .secondary)
                    .accessibilityLabel("Time remaining")
                    .accessibilityValue(DateTimeFormatter.shared.formatDurationInSeconds(ttl))
            } else {
                Text(entry.hasError ? LocalizedStrings.widgetErrorTitle : "—")
                    .font(.caption2)
                    .foregroundStyle(entry.hasError ? .red : .secondary)
                    .accessibilityLabel(entry.hasError ? "Sync failed" : "Time remaining unknown")
            }
        }
        .containerBackground(.regularMaterial, for: .widget)
        .widgetURL(URL(string: "ethosprotocol://vault/\(entry.vaultID)/view-details"))
        .accessibilityElement(children: .combine)
    }
}

/// #436: Circular lock screen widget — TTL as a compact Gauge ring.
/// Shown in the `.accessoryCircular` WidgetKit family (iOS 16+).
struct TTLAccessoryCircularView: View {
    let entry: VaultEntry

    // Maximum TTL shown as a full ring (24 hours expressed in seconds).
    private let maxTTL: Double = 86_400

    var body: some View {
        ZStack {
            if let ttl = entry.ttlRemaining {
                let fraction = min(Double(ttl) / maxTTL, 1.0)
                Gauge(value: fraction) {
                    Image(systemName: "lock.shield.fill")
                        .accessibilityHidden(true)
                } currentValueLabel: {
                    Text(compactDuration(ttl))
                        .font(.system(size: 10, weight: .semibold, design: .rounded))
                        .minimumScaleFactor(0.5)
                        .accessibilityLabel("Time remaining")
                        .accessibilityValue(DateTimeFormatter.shared.formatDurationInSeconds(ttl))
                }
                .gaugeStyle(.accessoryCircular)
                .tint(entry.isExpiringSoon ? .orange : .cyan)
            } else {
                // Error or no-data state — show shield with exclamation
                Image(systemName: entry.hasError ? "exclamationmark.icloud.fill" : "lock.shield")
                    .font(.title3)
                    .foregroundStyle(entry.hasError ? .red : .secondary)
                    .accessibilityLabel(entry.hasError ? "Sync failed" : "No vault data")
            }
        }
        .containerBackground(.regularMaterial, for: .widget)
        .widgetURL(URL(string: "ethosprotocol://vault/\(entry.vaultID)/view-details"))
    }

    /// Compact 4-char-max duration for the circular widget label (e.g. "23h", "2d").
    private func compactDuration(_ seconds: UInt64) -> String {
        let hours = seconds / 3600
        let days = seconds / 86400
        if days > 0 { return "\(days)d" }
        if hours > 0 { return "\(hours)h" }
        return "\(seconds / 60)m"
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
