import Foundation

/**
 Which history rows are uploaded, and what their row in the history list says.

 One place for both, because they disagreed. `SyncService` has only ever uploaded
 Feedback and Contribute rows, while `HistoryItemView` showed a "Pending" upload
 cloud on every row, scans included. A private scan that will never leave the
 device was labelled as waiting to upload, which is the opposite of what the
 privacy screen promises.
 */
nonisolated enum SyncPolicy {
    /// Attempts a row gets before it stops being retried.
    static let maxAttempts = 5

    /// History categories whose rows are uploaded on submit. "contribute" is an
    /// older spelling of "contribution" that rows may still carry.
    static let uploadedCategories: Set<String> = ["feedback", "contribution", "contribute"]

    static func uploads(category: String) -> Bool {
        uploadedCategories.contains(category)
    }

    enum Badge: Equatable, Sendable {
        case synced
        case pending
        /// Out of attempts: nothing will retry it, so it must not say pending.
        case failed
    }

    /// The badge for a history row, or nil for a row that is never uploaded.
    static func badge(category: String, isSynced: Bool, attempts: Int) -> Badge? {
        guard uploads(category: category) else { return nil }
        if isSynced { return .synced }
        return attempts >= maxAttempts ? .failed : .pending
    }
}
