import Foundation

struct LocalizedStrings {
    // MARK: - Check-in Reminders
    static let checkInReminderTitle = NSLocalizedString("Check-in Reminder", comment: "Notification title for check-in reminder")
    static let checkInUrgentTitle = NSLocalizedString("Check-in Urgent", comment: "Notification title for urgent check-in reminder")

    static func checkInReminderBody(vaultID: String, timeRemaining: String) -> String {
        NSLocalizedString(
            "Vault \(vaultID) expires in \(timeRemaining). Tap to check in and keep it active.",
            comment: "Check-in reminder notification body with vault ID and time remaining"
        )
    }

    static func checkInUrgentBody(vaultID: String, timeRemaining: String) -> String {
        NSLocalizedString(
            "Vault \(vaultID) expires in \(timeRemaining). Check in now to prevent loss of access.",
            comment: "Urgent check-in reminder notification body"
        )
    }

    // MARK: - Queued Check-in Notification
    static let queuedCheckInTitle = NSLocalizedString("Check-in queued", comment: "Notification title for queued check-in")

    static func queuedCheckInBody(count: Int) -> String {
        if count == 1 {
            return NSLocalizedString("1 check-in will be submitted when back online", comment: "Single queued check-in message")
        } else {
            return String(format: NSLocalizedString("%d check-ins will be submitted when back online", comment: "Multiple queued check-ins message"), count)
        }
    }

    // MARK: - Vault Expired Notification
    static let vaultExpiredTitle = NSLocalizedString("Check-in Failed — Vault Expired", comment: "Vault expired notification title")
    static let vaultExpiredBody = NSLocalizedString(
        "A queued check-in was discarded because this vault already expired while you were offline. The vault may have released funds to the beneficiary.",
        comment: "Vault expired notification body"
    )

    // MARK: - TTL Warning
    static let ttlWarningTitle = NSLocalizedString("Vault Expiring Soon", comment: "TTL warning notification title")

    static func ttlWarningBody(vaultID: String, timeRemaining: String) -> String {
        NSLocalizedString(
            "Vault \(vaultID) expires in \(timeRemaining). Open the app to check in and keep it active.",
            comment: "TTL warning notification body"
        )
    }

    // MARK: - Widget Strings
    static let widgetTitle = NSLocalizedString("Ethos-Protocol", comment: "Widget title/app name")
    static let myVault = NSLocalizedString("My Vault", comment: "Default vault name in widget")
    static let noActiveVault = NSLocalizedString("No Active Vault", comment: "Widget message when no active vaults")
    static let unavailable = NSLocalizedString("Unavailable", comment: "Widget message when vault data unavailable")
    static let expiringsoon = NSLocalizedString("Expiring soon", comment: "Widget label for soon-to-expire vault")
    static let ttlLabel = NSLocalizedString("TTL", comment: "Widget label for time-to-live")
    static let balanceLabel = NSLocalizedString("Balance", comment: "Widget label for balance")
    static let beneficiaryLabel = NSLocalizedString("Beneficiary", comment: "Widget label for beneficiary")

    static func durationFormat(days: UInt64, hours: UInt64) -> String {
        if days > 0 {
            return String(format: NSLocalizedString("%dd %dh remaining", comment: "Duration format with days and hours"), days, hours)
        }
        return String(format: NSLocalizedString("%dh remaining", comment: "Duration format with hours only"), hours)
    }

    // MARK: - Widget Error State (#435)
    static let widgetErrorTitle = NSLocalizedString("Sync Failed", comment: "Widget error state title shown when data fetch fails")
    static let widgetErrorMessage = NSLocalizedString("Tap to retry", comment: "Widget error state prompt shown below error title")

    // MARK: - Check-in Action
    static let checkInAction = NSLocalizedString("Check In", comment: "Notification action to check in")
}
