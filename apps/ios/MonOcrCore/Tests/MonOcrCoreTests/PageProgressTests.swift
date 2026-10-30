import Foundation
import Testing

@testable import MonOcrCore

/// How far a multi-page PDF has got, for the determinate progress bar that
/// replaced an open-ended shimmer.
struct PageProgressTests {
    /// A page that failed is finished as far as waiting goes; only pages not
    /// yet attempted are still to come.
    @Test func progressCountsEveryFinishedPage() {
        let outcomes = [
            PageOutcome(pageIndex: 0, state: .completed, error: nil),
            PageOutcome(pageIndex: 1, state: .renderFailed, error: nil),
            PageOutcome(pageIndex: 2, state: .notAttempted, error: nil),
            PageOutcome(pageIndex: 3, state: .notAttempted, error: nil),
        ]
        let progress = PageProgress(outcomes: outcomes)
        #expect(progress.done == 2)
        #expect(progress.total == 4)
        #expect(progress.fraction == 0.5)
    }

    @Test func progressIsClampedAndSafeOnAnEmptyDocument() {
        #expect(PageProgress(done: 5, total: 3).done == 3)
        #expect(PageProgress(done: -1, total: 3).done == 0)
        #expect(PageProgress(outcomes: []).fraction == 0)
    }
}
