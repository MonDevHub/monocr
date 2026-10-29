import Foundation

enum EngineStatus: Equatable {
    case loading
    case ready
    /// A scan is running. `progress` is nil until the page count is known,
    /// and stays nil for a single image.
    case processing(PageProgress?)
    case error(String)
}

/// How far a multi-page read has got.
nonisolated struct PageProgress: Equatable, Sendable {
    let done: Int
    let total: Int

    init(done: Int, total: Int) {
        self.total = max(0, total)
        self.done = min(max(0, done), self.total)
    }

    /// Pages that have finished in any state. A page that failed to render is
    /// as finished as one that was read: the count is about what is left to
    /// wait for, not about what succeeded.
    init(outcomes: [PageOutcome]) {
        self.init(done: outcomes.filter { $0.state != .notAttempted }.count, total: outcomes.count)
    }

    var fraction: Double { total > 0 ? Double(done) / Double(total) : 0 }
}
