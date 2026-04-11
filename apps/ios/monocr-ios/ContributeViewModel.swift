import Foundation
import SwiftData
import UIKit
import Combine

@MainActor
final class ContributeViewModel: ObservableObject {
    @Published var transcription: String = ""
    @Published var sourceImage: UIImage? = nil
    @Published var originalPdfData: Data? = nil
    @Published var selectedFileName: String? = nil
    
    var isSubmitDisabled: Bool {
        transcription.isEmpty && sourceImage == nil && originalPdfData == nil
    }

    func handleSelectedPDF(at url: URL) {
        guard url.startAccessingSecurityScopedResource() else { return }
        defer { url.stopAccessingSecurityScopedResource() }
        
        do {
            let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
            if let fileSize = attributes[.size] as? Int64, fileSize > 20 * 1024 * 1024 {
                MonLog_w("PDF file too large for this version (max 20MB): \(url.lastPathComponent)")
                return
            }
            
            let data = try Data(contentsOf: url)
            self.originalPdfData = data
            self.selectedFileName = url.lastPathComponent
            
            // Render first page for UI preview
            if let previewImage = PdfUtil.renderPdfPageToImage(at: url, pageIndex: 0) {
                self.sourceImage = previewImage
            }
            MonLog_i("Loaded PDF document: \(url.lastPathComponent) (\(data.count) bytes)")
        } catch {
            MonLog_e("Failed to load PDF document", error: error)
        }
    }

    func submitContribution(context: ModelContext) {
        let isPdf = originalPdfData != nil
        let defaultTitle = sourceImage != nil ? "Image Contribution" : "Text Contribution"
        let title = selectedFileName ?? defaultTitle
        
        let record = HistoryRecord(
            timestamp: Date(),
            fileName: title,
            fileType: isPdf ? "application/pdf" : (sourceImage != nil ? "image/jpeg" : "text/plain"),
            text: transcription.isEmpty ? "(Image only)" : transcription,
            processingTimeMs: 0,
            category: "contribution",
            imageData: originalPdfData ?? sourceImage?.jpegData(compressionQuality: 0.6)
        )

        context.insert(record)
        try? context.save()
        
        // Trigger immediate sync on background actor
        Task { await SyncService.shared.syncAll() }
        
        // Reset form
        transcription = ""
        sourceImage = nil
        originalPdfData = nil
        selectedFileName = nil
    }
}
