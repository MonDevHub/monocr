import Foundation

/// Mutated on the main actor only. An old asynchronous completion cannot publish again.
nonisolated struct OcrJobGeneration {
    private(set) var current = UUID()
    mutating func advance() -> UUID {
        current = UUID()
        return current
    }
    func accepts(_ token: UUID) -> Bool { current == token }
}

/// Exactly one stop for a successful acquisition, including cancellation/error paths.
nonisolated final class ScopedResourceLease {
    let acquired: Bool
    private let lock = NSLock()
    private var release: (() -> Void)?

    init(start: () -> Bool, stop: @escaping () -> Void) {
        acquired = start()
        release = acquired ? stop : nil
    }

    func close() {
        lock.lock()
        let action = release
        release = nil
        lock.unlock()
        action?()
    }

    deinit { close() }
}
