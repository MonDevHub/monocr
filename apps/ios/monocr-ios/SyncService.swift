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
    
    private var isSyncing = false
    private var modelContainer: ModelContainer?
    
    func initialize(with container: ModelContainer) {
        self.modelContainer = container
        
        // Initial sync on launch
        Task { await syncAll() }
        
        // Setup 5 minute polling
        Timer.scheduledTimer(withTimeInterval: 300, repeats: true) { _ in
            Task { await self.syncAll() }
        }
    }
    
    func syncAll() async {
        guard !Self.API_KEY.isEmpty else { return }
        guard !isSyncing, let container = modelContainer else { return }
        isSyncing = true
        defer { isSyncing = false }
        
        let context = ModelContext(container)
        
        do {
            // Fetch only IDs to prevent loading multiple records + Data blobs into memory at once
            let descriptor = FetchDescriptor<HistoryRecord>(
                predicate: #Predicate { $0.isSynced == false }
            )
            let records = try context.fetch(descriptor)
            
            for record in records {
                let isAllowed = record.category == "contribution" || record.category == "feedback" || record.category == "contribute"
                if isAllowed && record.syncAttempts < 5 {
                    await syncRecord(record.id, in: container)
                }
            }
        } catch {
            MonLog_e("SyncAll Error", error: error)
        }
    }
    
    private func syncRecord(_ recordId: UUID, in container: ModelContainer) async {
        let context = ModelContext(container)
        
        // Fetch specific record in this context
        let descriptor = FetchDescriptor<HistoryRecord>(
            predicate: #Predicate { $0.id == recordId }
        )
        guard let record = try? context.fetch(descriptor).first else { return }
        
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
                    data: textData
                )
                uploads += 1
            }

            guard uploads > 0 else {
                // Nothing was sent, so nothing is synced. Left eligible rather than
                // marked done; `syncAttempts < 5` bounds the retrying.
                MonLog_e("Sync produced nothing to upload for \(record.fileName)")
                record.syncError = "Nothing to upload: the record carries neither a file nor any text."
                record.syncAttempts += 1
                try context.save()
                return
            }

            record.isSynced = true
            record.syncError = nil
            record.syncAttempts += 1
            try context.save()
            MonLog_i("Successfully synced: \(record.fileName) (\(uploads) upload(s))")
        } catch {
            MonLog_e("Sync failed for \(record.fileName)", error: error)
            record.syncError = error.localizedDescription
            record.syncAttempts += 1
            try? context.save()
        }
    }
    
    /// Strip what would let a value break out of the header it sits in.
    ///
    /// The filename is user-controlled and was interpolated straight into
    /// `Content-Disposition: ...; filename="\(fileName)"`. A document named
    /// `x".txt"\r\nContent-Type: text/html\r\n\r\n...` injected arbitrary
    /// multipart headers, or an entire extra part, into the request this app makes
    /// with its own API key. Android carried the same hole.
    ///
    /// CR and LF go because they end a header; the double quote goes because it
    /// ends the quoted string. Everything else survives, so Mon titles stay intact
    /// rather than being reduced to underscores.
    ///
    /// `original_name` above is a body field rather than a header parameter, so a
    /// quote in it is harmless; it is left alone so the service receives the real
    /// name.
    private func headerSafe(_ value: String) -> String {
        value
            .replacingOccurrences(of: "\r", with: "")
            .replacingOccurrences(of: "\n", with: "")
            .replacingOccurrences(of: "\"", with: "'")
    }

    /// Memory-efficient multipart upload using temporary file backing
    private func uploadToFeedbackService(fileName: String, fileType: String, recordId: String, category: String, data: Data) async throws {
        let categoryLower = category.lowercased()
        let endpointSuffix = (categoryLower == "contribution" || categoryLower == "contribute") ? "contribution" : "feedback"
        let endpointString = "\(Self.BASE_URL)/\(endpointSuffix)"
        
        guard let url = URL(string: endpointString) else { throw URLError(.badURL) }
        
        let boundary = "Boundary-\(UUID().uuidString)"
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.setValue(Self.API_KEY, forHTTPHeaderField: "X-API-Key")
        request.setValue(recordId, forHTTPHeaderField: "X-Request-ID")
        
        // --- Memory-Efficient Body Construction (File Backed) ---
        let tempDir = FileManager.default.temporaryDirectory
        let tempFileURL = tempDir.appendingPathComponent("upload-\(UUID().uuidString).tmp")
        
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        
        // Start streaming to file
        let stream = OutputStream(url: tempFileURL, append: false)!
        stream.open()
        defer { stream.close() }
        
        func write(_ string: String) {
            let data = string.data(using: .utf8)!
            _ = data.withUnsafeBytes { stream.write($0.bindMemory(to: UInt8.self).baseAddress!, maxLength: data.count) }
        }
        
        func write(_ data: Data) {
            _ = data.withUnsafeBytes { stream.write($0.bindMemory(to: UInt8.self).baseAddress!, maxLength: data.count) }
        }
        
        // Construct multipart body in the file
        write("--\(boundary)\r\n")
        write("Content-Disposition: form-data; name=\"record_id\"\r\n\r\n")
        write("\(recordId)\r\n")
        
        write("--\(boundary)\r\n")
        write("Content-Disposition: form-data; name=\"original_name\"\r\n\r\n")
        write("\(fileName)\r\n")
        
        write("--\(boundary)\r\n")
        write("Content-Disposition: form-data; name=\"file\"; filename=\"\(headerSafe(fileName))\"\r\n")
        write("Content-Type: \(headerSafe(fileType))\r\n\r\n")
        write(data) // The large data blob is still passed as Data here, but it's not copied into a second Data object.
        write("\r\n")
        
        write("--\(boundary)--\r\n")
        
        // Perform the upload from file (avoids loading the entire reconstructed body into RAM)
        let (_, response) = try await URLSession.shared.upload(for: request, fromFile: tempFileURL)
        
        // Cleanup
        try? FileManager.default.removeItem(at: tempFileURL)
        
        if let httpResponse = response as? HTTPURLResponse, !(200...299).contains(httpResponse.statusCode) {
            throw NSError(domain: "FeedbackServiceSync", code: httpResponse.statusCode, userInfo: [NSLocalizedDescriptionKey : "HTTP \(httpResponse.statusCode)"])
        }
    }
}

/// Global actor for synchronization to ensure it doesn't starve the main thread
@globalActor actor SyncActor {
    static let shared = SyncActor()
}
