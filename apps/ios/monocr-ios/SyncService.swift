import Foundation
import SwiftData
import CryptoKit

actor SyncService {
    static let shared = SyncService()
    
    private static let BASE_URL = "https://ocr-feedback-service-857115062313.asia-southeast1.run.app/v1"

    /// Supplied at build time, never in source.
    ///
    /// This was a 64-character literal on the line below from 2026-04-11 to
    /// 2026-08-16, in a public repository, directly beneath the production
    /// endpoint it authenticates against. Treat that value as burned regardless
    /// of this change: it is in the git history and in every shipped IPA.
    ///
    /// Set `SYNC_API_KEY` in an xcconfig that is not committed (it reaches
    /// Info.plist through `GENERATE_INFOPLIST_FILE`), or export
    /// `MONOCR_SYNC_API_KEY` when running from Xcode. Empty is a supported
    /// state: sync is skipped and everything else in the app works.
    private static let API_KEY: String = {
        if let key = Bundle.main.object(forInfoDictionaryKey: "SYNC_API_KEY") as? String,
           !key.isEmpty {
            return key
        }
        return ProcessInfo.processInfo.environment["MONOCR_SYNC_API_KEY"] ?? ""
    }()

    /// How long between retry passes, and how many attempts a record gets.
    ///
    /// The cap is named because eligibility is now decided in two places — the
    /// pass that picks records up, and the count of what is left to retry that
    /// decides whether polling keeps running. Two literal `5`s that must agree is
    /// how the poll loop ends up either spinning forever or stopping early.
    private static let pollIntervalSeconds: UInt64 = 300
    private static let maxSyncAttempts = 5
    
    private var isSyncing = false
    private var modelContainer: ModelContainer?

    /// The retry loop, and a token identifying the one that is current.
    ///
    /// The token exists so a loop that is finishing cannot clear a loop that was
    /// started after it.
    private var pollTask: Task<Void, Never>?
    private var pollToken: UUID?

    /// Set when a `syncAll` arrived while a pass was already in flight.
    ///
    /// `isSyncing` DROPS that call — it always has — and the dropped call is
    /// usually `ContributeViewModel` or `FeedbackViewModel` reacting to a fresh
    /// submit, whose record was not in the running pass's fetch. Without this flag
    /// a pass can report an empty queue it never actually looked at, the retry loop
    /// exits on that report, and the just-submitted record waits for the next
    /// launch.
    private var resyncRequested = false
    
    func initialize(with container: ModelContainer) {
        self.modelContainer = container

        // The initial pass arms the retry loop if it leaves anything behind, so
        // there is nothing to schedule here.
        Task { await syncAll() }
    }

    /// Run a sync pass, and keep retrying if it did not finish the queue.
    ///
    /// Deliberately still `-> Void`: `ContributeViewModel` and `FeedbackViewModel`
    /// call this and discard the result.
    func syncAll() async {
        switch await runSyncPass() {
        case .unavailable:
            break
        case .alreadyRunning:
            // The in-flight pass fetched before this record existed, so make sure
            // something comes back for it.
            armPoll()
        case .ran(let pendingAfter):
            if pendingAfter > 0 { armPoll() }
        }
    }

    /// Start the retry loop unless one is already running.
    ///
    /// This replaces a `Timer.scheduledTimer(withTimeInterval: 300, ...)` that
    /// NEVER FIRED, for the whole life of the feature. `Timer` schedules onto
    /// `RunLoop.current`, and `initialize(with:)` is an `actor` method invoked as
    /// `Task { await SyncService.shared.initialize(...) }` from
    /// `monocr_iosApp.swift`, so `current` is a cooperative-pool thread whose run
    /// loop nobody runs. The timer was created, retained by nothing that would
    /// ever tick it, and dropped.
    ///
    /// What that cost: iOS synced once per launch and once per explicit submit,
    /// and nothing else. With `maxSyncAttempts` attempts available and only one
    /// spent per launch, a record that met a transient outage across five launches
    /// exhausted its attempts and became permanently ineligible, with no retry and
    /// nothing in the UI saying so. A `Task` with `Task.sleep` fires from actor
    /// context, needs no run loop, and cancels at the sleep.
    ///
    /// Called from `syncAll`, so calling `initialize` twice cannot start a second
    /// loop.
    private func armPoll() {
        guard pollTask == nil else { return }
        let token = UUID()
        pollToken = token
        pollTask = Task { [weak self] in
            await self?.pollLoop(token: token)
        }
    }

    /// Sleep, sync, and stop once there is nothing left to retry.
    ///
    /// Stopping matters: a loop that runs forever wakes the app every five minutes
    /// to fetch an empty result set for the rest of the session. `syncAll` arms a
    /// fresh loop the next time a pass leaves work behind, so the only thing lost
    /// by exiting is the wake-up.
    private func pollLoop(token: UUID) async {
        defer {
            // Synchronous on the actor, so `armPoll` cannot interleave and have
            // its new loop cleared here.
            if pollToken == token {
                pollToken = nil
                pollTask = nil
            }
        }

        while true {
            do {
                try await Task.sleep(nanoseconds: Self.pollIntervalSeconds * 1_000_000_000)
            } catch {
                return  // cancelled
            }
            guard pollToken == token else { return }

            switch await runSyncPass() {
            case .unavailable:
                // No API key, or no container. Neither changes without a relaunch.
                return
            case .alreadyRunning:
                continue
            case .ran(let pendingAfter):
                if pendingAfter == 0 && !resyncRequested { return }
            }
        }
    }

    /// What one pass established about whether retrying is worth scheduling.
    private enum SyncPassResult {
        /// Sync cannot run in this session at all: no API key, or no container yet.
        case unavailable
        /// Another pass holds `isSyncing`; that pass's own result decides polling.
        case alreadyRunning
        /// Ran to the end. `pendingAfter` records are still eligible for a retry.
        case ran(pendingAfter: Int)
    }

    private func runSyncPass() async -> SyncPassResult {
        guard !Self.API_KEY.isEmpty else { return .unavailable }
        guard let container = modelContainer else { return .unavailable }
        guard !isSyncing else {
            resyncRequested = true
            return .alreadyRunning
        }
        isSyncing = true
        // Cleared on the way in, so a request arriving DURING this pass survives it.
        resyncRequested = false
        defer { isSyncing = false }
        
        let context = ModelContext(container)
        
        do {
            // Fetches whole records, `imageData` blobs included — the predicate
            // narrows the rows, not the columns. The comment here used to claim it
            // fetched only IDs, which it never did; `syncRecord` re-fetches by ID
            // into its own context, which is what keeps the blob out of TWO
            // contexts at once, not out of this one.
            let descriptor = FetchDescriptor<HistoryRecord>(
                predicate: #Predicate { $0.isSynced == false }
            )
            let records = try context.fetch(descriptor)
            
            var pendingAfter = 0
            for record in records {
                let isAllowed = record.category == "contribution" || record.category == "feedback" || record.category == "contribute"
                guard isAllowed, record.syncAttempts < Self.maxSyncAttempts else {
                    // Wrong category or out of attempts: not pending, because no
                    // amount of retrying will pick it up.
                    continue
                }
                if await syncRecord(record.id, in: container) {
                    pendingAfter += 1
                }
            }
            return .ran(pendingAfter: pendingAfter)
        } catch {
            MonLog_e("SyncAll Error", error: error)
            // The fetch failed, so nothing is known about the queue. Reported as
            // outstanding rather than empty, so the loop tries again.
            return .ran(pendingAfter: 1)
        }
    }

    /// Attempt one record. Returns whether it is still eligible for a later retry.
    private func syncRecord(_ recordId: UUID, in container: ModelContainer) async -> Bool {
        let context = ModelContext(container)
        
        // Fetch specific record in this context
        let descriptor = FetchDescriptor<HistoryRecord>(
            predicate: #Predicate { $0.id == recordId }
        )
        guard let record = try? context.fetch(descriptor).first else { return false }
        
        MonLog_d("[Sync] Attempting \(record.fileName)...")
        
        do {
            // Counted, because `isSynced` must mean "the server has it" and used to
            // mean "no error was thrown".
            //
            // A text-only contribution destroyed itself here. `ContributeViewModel`
            // names an unattached contribution "Text Contribution", so
            // `record.imageData` was nil AND the sentinel below matched: no primary
            // upload, text upload skipped, `isSynced = true`. The user saw success,
            // history showed synced, and the server never received the text. There
            // is no recovery from that, because the record is no longer eligible.
            //
            // The sentinel was ported from web, where `fileData` is unconditionally
            // the text blob, so skipping the second upload correctly avoided sending
            // it twice. Here the primary payload is conditional, which inverted the
            // guard's meaning. The condition that actually matters is whether the
            // text has already gone up as the primary payload, so that is what this
            // asks instead of comparing a display name.
            var uploads = 0

            if let imageData = record.imageData {
                try await uploadToFeedbackService(
                    fileName: record.fileName,
                    fileType: record.fileType,
                    recordId: record.id.uuidString,
                    category: record.category,
                    slot: .file,
                    data: imageData
                )
                uploads += 1
            }

            let hasTranscription = !record.text.isEmpty && record.text != "(Image only)"
            if hasTranscription, let textData = record.text.data(using: .utf8) {
                // `-transcription` only when it accompanies something. When the text
                // IS the contribution it is not a transcription OF anything, and a
                // suffix would misfile it in the corpus.
                let base = (record.fileName as NSString).deletingPathExtension
                let textFileName = record.imageData == nil ? "\(base).txt" : "\(base)-transcription.txt"
                try await uploadToFeedbackService(
                    fileName: textFileName,
                    fileType: "text/plain",
                    recordId: record.id.uuidString,
                    category: record.category,
                    slot: .transcription,
                    data: textData
                )
                uploads += 1
            }

            guard uploads > 0 else {
                // Nothing was sent, so nothing is synced. Left eligible rather than
                // marked done; `maxSyncAttempts` bounds the retrying.
                MonLog_e("Sync produced nothing to upload for \(record.fileName)")
                record.syncError = "Nothing to upload: the record carries neither a file nor any text."
                record.syncAttempts += 1
                try context.save()
                return record.syncAttempts < Self.maxSyncAttempts
            }

            record.isSynced = true
            record.syncError = nil
            record.syncAttempts += 1
            try context.save()
            MonLog_i("Successfully synced: \(record.fileName) (\(uploads) upload(s))")
            return false
        } catch {
            MonLog_e("Sync failed for \(record.fileName)", error: error)
            record.syncError = error.localizedDescription
            record.syncAttempts += 1
            try? context.save()
            return record.syncAttempts < Self.maxSyncAttempts
        }
    }

    /// Which of the two requests a record can make this one is.
    ///
    /// Both payloads went out with `X-Request-ID` set to the bare record id, so a
    /// service that deduped on that header — the header's conventional meaning —
    /// would drop the second one, and no iOS transcription would ever land. The
    /// feedback service does not dedupe today (`middleware/trace.go` only echoes
    /// the value and scopes a logger to it), so the cost so far has been that the
    /// two uploads of one record are indistinguishable in the service logs. The
    /// suffix removes the latent hazard and the log ambiguity together.
    ///
    /// These two raw values are a wire contract shared with Android's `SyncPayload`
    /// in `apps/android/.../engine/SyncWorker.kt`, changed in the same pass. Two
    /// independent codebases depend on them being identical, so they are not
    /// something to tidy. The
    /// `record_id` FORM FIELD stays the bare id, because the service builds the
    /// object key from it. The suffix names the request slot, not the semantic role
    /// — a text-only contribution has no primary payload, and its single text
    /// upload still goes out as `transcription`.
    private enum UploadSlot: String {
        case file
        case transcription
    }

    /// Memory-efficient multipart upload using temporary file backing
    private func uploadToFeedbackService(fileName: String, fileType: String, recordId: String, category: String, slot: UploadSlot, data: Data) async throws {
        let categoryLower = category.lowercased()
        let endpointSuffix = (categoryLower == "contribution" || categoryLower == "contribute") ? "contribution" : "feedback"
        let endpointString = "\(Self.BASE_URL)/\(endpointSuffix)"
        
        guard let url = URL(string: endpointString) else { throw URLError(.badURL) }
        
        // --- Memory-Efficient Body Construction (File Backed) ---
        let tempDir = FileManager.default.temporaryDirectory
        let tempFileURL = tempDir.appendingPathComponent("upload-\(UUID().uuidString).tmp")
        
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)

        // Deferred before the file exists, because the cleanup used to be a plain
        // statement after the upload and so was SKIPPED on every throw. Each failed
        // 20MB PDF left 20MB in `tmp`, per attempt, until the OS decided to reclaim
        // it. A `defer` here runs on the throwing paths too.
        defer { try? FileManager.default.removeItem(at: tempFileURL) }

        let boundary = "Boundary-\(UUID().uuidString)"
        let body = MultipartFormBody.uploadBody(
            boundary: boundary,
            recordId: recordId,
            fileName: fileName,
            fileType: fileType,
            data: data
        )

        // Written, closed and checked in three separate steps, because the previous
        // version did all of it after `URLSession` already had the file. The
        // `defer { stream.close() }` fired at FUNCTION exit, which is after the
        // upload, so the body was handed over by a still-open, still-unflushed
        // writer.
        let intendedByteCount = try Self.writeBody(body, to: tempFileURL)

        let onDiskByteCount = (try FileManager.default.attributesOfItem(atPath: tempFileURL.path)[.size] as? NSNumber)?.intValue ?? -1
        guard onDiskByteCount == intendedByteCount else {
            // The writes all reported success and the file still is not the right
            // size. Throwing here is the difference between a retry and a truncated
            // object on the server under a record marked `isSynced`.
            throw MultipartWriteError.sizeMismatch(intended: intendedByteCount, onDisk: onDiskByteCount)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.setValue(Self.API_KEY, forHTTPHeaderField: "X-API-Key")
        request.setValue("\(recordId):\(slot.rawValue)", forHTTPHeaderField: "X-Request-ID")
        // URLSession owns `Content-Length` for a file upload and derives it from the
        // file, so this is a declaration of intent rather than the guard; the guard
        // is the size check above, which has already established the two agree.
        request.setValue("\(intendedByteCount)", forHTTPHeaderField: "Content-Length")

        // Perform the upload from file (avoids loading the entire reconstructed body into RAM)
        let (_, response) = try await URLSession.shared.upload(for: request, fromFile: tempFileURL)
        
        if let httpResponse = response as? HTTPURLResponse, !(200...299).contains(httpResponse.statusCode) {
            throw NSError(domain: "FeedbackServiceSync", code: httpResponse.statusCode, userInfo: [NSLocalizedDescriptionKey : "HTTP \(httpResponse.statusCode)"])
        }
    }

    /// Write `body` to `fileURL` and return the bytes written, stream closed.
    ///
    /// Its own function so that `defer { stream.close() }` means "before the
    /// caller does anything with this file" rather than "at the end of the upload".
    private static func writeBody(_ body: MultipartFormBody, to fileURL: URL) throws -> Int {
        guard let stream = OutputStream(url: fileURL, append: false) else {
            throw MultipartWriteError.cannotOpenFile(fileURL)
        }
        stream.open()
        defer { stream.close() }

        guard stream.streamStatus == .open else {
            throw MultipartWriteError.sinkRejectedBytes(attempted: 0, accepted: 0, underlying: stream.streamError)
        }

        return try body.write(to: stream)
    }
}

/// Global actor for synchronization to ensure it doesn't starve the main thread
@globalActor actor SyncActor {
    static let shared = SyncActor()
}
