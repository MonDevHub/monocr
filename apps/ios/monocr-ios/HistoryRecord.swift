import Foundation
import SwiftData

@Model
final class HistoryRecord {
    @Attribute(.unique) var id: UUID
    var timestamp: Date
    var fileName: String
    var fileType: String
    var text: String
    var processingTimeMs: Int
    var category: String // "ocr-scan", "contribution", "feedback"
    @Attribute(.externalStorage) var imageData: Data?
    var isSynced: Bool
    var syncAttempts: Int
    var syncError: String?
    
    init(
        id: UUID = UUID(),
        timestamp: Date = Date(),
        fileName: String,
        fileType: String,
        text: String,
        processingTimeMs: Int = 0,
        category: String,
        imageData: Data? = nil,
        isSynced: Bool = false,
        syncAttempts: Int = 0,
        syncError: String? = nil
    ) {
        self.id = id
        self.timestamp = timestamp
        self.fileName = fileName
        self.fileType = fileType
        self.text = text
        self.processingTimeMs = processingTimeMs
        self.category = category
        self.imageData = imageData
        self.isSynced = isSynced
        self.syncAttempts = syncAttempts
        self.syncError = syncError
    }
}

