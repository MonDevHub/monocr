import Foundation
import SwiftData
import CryptoKit

actor SyncService {
    static let shared = SyncService()
    
    private static let BASE_URL = "https://ocr-feedback-service-857115062313.asia-southeast1.run.app/v1"
    private static let API_KEY = "a47102547a8db8aa2fa454441b04bbb3780fcc5f66f976159994315f003d209a"
    
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
            if let imageData = record.imageData {
                try await uploadToFeedbackService(
                    fileName: record.fileName,
                    fileType: record.fileType,
                    recordId: record.id.uuidString,
                    category: record.category,
                    data: imageData
                )
            }
            
            // Dual upload text if not raw image-only or fallback
            let isJustTextBlob = record.fileName == "Text Contribution"
            if !isJustTextBlob && !record.text.isEmpty && record.text != "(Image only)" {
                let textFileName = "\((record.fileName as NSString).deletingPathExtension)-transcription.txt"
                if let textData = record.text.data(using: .utf8) {
                    try await uploadToFeedbackService(
                        fileName: textFileName,
                        fileType: "text/plain",
                        recordId: record.id.uuidString,
                        category: record.category,
                        data: textData
                    )
                }
            }
            
            record.isSynced = true
            record.syncError = nil
            record.syncAttempts += 1
            try context.save()
            MonLog_i("Successfully synced: \(record.fileName)")
        } catch {
            MonLog_e("Sync failed for \(record.fileName)", error: error)
            record.syncError = error.localizedDescription
            record.syncAttempts += 1
            try? context.save()
        }
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
        write("Content-Disposition: form-data; name=\"file\"; filename=\"\(fileName)\"\r\n")
        write("Content-Type: \(fileType)\r\n\r\n")
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
