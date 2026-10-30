import Foundation
import Testing

@testable import MonOcrCore

/// Which history rows say anything about uploading. A scan is private and never
/// uploaded; the list used to badge it "Pending" with an upload cloud anyway.
struct SyncPolicyTests {
    /// A scan is never uploaded, so it must not carry an upload badge. Every
    /// scan row showed "Pending" with an upload cloud.
    @Test func aScanRowHasNoUploadBadge() {
        #expect(SyncPolicy.badge(category: "scan", isSynced: false, attempts: 0) == nil)
        #expect(!SyncPolicy.uploads(category: "scan"))
    }

    @Test func uploadedRowsSayWhereTheyStand() {
        #expect(SyncPolicy.badge(category: "feedback", isSynced: false, attempts: 0) == .pending)
        #expect(SyncPolicy.badge(category: "contribution", isSynced: true, attempts: 1) == .synced)
        #expect(SyncPolicy.badge(category: "contribute", isSynced: false, attempts: 2) == .pending)
    }

    /// Out of attempts, nothing retries the row, so "pending" would be a promise
    /// the app will not keep.
    @Test func aRowOutOfAttemptsSaysFailedNotPending() {
        let exhausted = SyncPolicy.badge(category: "feedback", isSynced: false, attempts: SyncPolicy.maxAttempts)
        #expect(exhausted == .failed)
        let lastTry = SyncPolicy.badge(category: "feedback", isSynced: false, attempts: SyncPolicy.maxAttempts - 1)
        #expect(lastTry == .pending)
    }
}
