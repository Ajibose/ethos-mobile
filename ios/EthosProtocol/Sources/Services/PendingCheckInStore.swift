import Foundation
import os.log

// ADR-0001: Offline-first architecture — see docs/adr/adr-0001-offline-first.md

// MARK: - PendingCheckIn

/// A single queued check-in that could not be delivered while the device was offline.
/// Mirrors the CHECK_IN case of Android's `PendingAction` Room entity (PendingAction.kt).
struct PendingCheckIn: Codable, Equatable {
    let vaultId: String
    let queuedAt: Date
    // Stable identifier set once when the check-in is first queued (not re-generated on
    // retry), sent as X-Idempotency-Key so a resubmission after the process is terminated
    // between the server accepting the request and this entry being deleted is identifiable
    // to the server as a duplicate of a specific prior attempt, not a brand-new check-in.
    var idempotencyKey: String = ""
}

// MARK: - PendingCheckInStore

/// Durable, disk-backed queue for offline check-ins.
///
/// Mirrors the CHECK_IN path of Android's `PendingActionDao` (PendingActionDatabase.kt) —
/// insert on offline check-in, delete on successful delivery, read by `CheckInSyncTask`
/// when connectivity returns.
///
/// Storage: a JSON file in the app's Application Support directory so it survives
/// app restarts (unlike UserDefaults, which can be evicted under storage pressure).
/// All mutations are synchronised on a serial queue to avoid data races.
final class PendingCheckInStore {
    static let shared = PendingCheckInStore()
    static let maxQueueSize = 50

    private let fileURL: URL
    private let queue = DispatchQueue(label: "com.ethosprotocol.PendingCheckInStore")

    // True if the most recent load() found a queue file that exists but couldn't be decoded
    // (e.g. captured mid-write). Exposed so tests can assert corruption is surfaced instead
    // of being silently swallowed as an empty queue.
    private(set) var lastLoadWasCorrupted = false

    // Injected for unit tests so we don't touch the real filesystem.
    init(fileURL: URL? = nil) {
        if let url = fileURL {
            self.fileURL = url
        } else {
            let support = FileManager.default
                .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("EthosProtocol", isDirectory: true)
            try? FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
            self.fileURL = support.appendingPathComponent("pending_checkins.json")
        }
    }

    // MARK: - Public API (matches PendingCheckInDao)

    /// Returns all queued check-ins, oldest first.
    func getAll() -> [PendingCheckIn] {
        queue.sync { load() }
    }

    /// Returns the current queue count (used by the notification badge).
    var count: Int { getAll().count }

    /// Returns true when the queue has reached its maximum capacity.
    var isAtCapacity: Bool { count >= Self.maxQueueSize }

    /// Returns true when the queue is within 5 items of its maximum capacity.
    var isNearCapacity: Bool { count >= Self.maxQueueSize - 5 }

    /// Enqueue a check-in for `vaultId`. Idempotent — a vault already in the queue
    /// is not duplicated (mirrors Android's `OnConflictStrategy.REPLACE`).
    func insert(_ item: PendingCheckIn) {
        queue.sync {
            var items = load()
            items.removeAll { $0.vaultId == item.vaultId }
            items.append(item)
            if items.count > Self.maxQueueSize { items.removeFirst(items.count - Self.maxQueueSize) }
            save(items)
        }
    }

    /// Remove a successfully-delivered check-in from the queue.
    func delete(_ item: PendingCheckIn) {
        queue.sync {
            var items = load()
            items.removeAll { $0.vaultId == item.vaultId }
            save(items)
        }
    }

    /// Wipe the entire queue (used in tests and on sign-out).
    func deleteAll() {
        queue.sync { save([]) }
    }

    // MARK: - Private helpers

    private func load() -> [PendingCheckIn] {
        guard let data = try? Data(contentsOf: fileURL) else {
            lastLoadWasCorrupted = false
            return []
        }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        if let items = try? decoder.decode([PendingCheckIn].self, from: data) {
            lastLoadWasCorrupted = false
            return items
        }
        // The file exists but couldn't be decoded (e.g. captured mid-write by a process
        // termination). Surface this rather than silently treating it as "no pending
        // check-ins" — a still-pending check-in living in that file is about to be dropped.
        lastLoadWasCorrupted = true
        os_log("PendingCheckInStore: queue file exists but failed to decode — treating as empty until next successful write", type: .error)
        return []
    }

    private func save(_ items: [PendingCheckIn]) {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = .prettyPrinted
        guard let data = try? encoder.encode(items) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }
}
