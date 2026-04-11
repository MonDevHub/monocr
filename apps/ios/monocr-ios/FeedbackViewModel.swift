import Foundation
import SwiftData
import UIKit
import Combine

@MainActor
final class FeedbackViewModel: ObservableObject {
    let originalText: String
    @Published var correctedText: String
    @Published var selectedType: String = "Spelling"
    @Published var consent: Bool = false
    @Published var sourceImage: UIImage? = nil
    @Published var originalPdfData: Data? = nil
    @Published var selectedFileName: String? = nil
    
    let errorTypes = ["Spelling", "Layout", "Formatting", "Other"]
    
    init(originalText: String, initialImage: UIImage? = nil) {
        self.originalText = originalText
        self.correctedText = originalText
        self.sourceImage = initialImage
    }
    
    var isSubmitDisabled: Bool {
        correctedText.isEmpty || !consent
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
            MonLog_i("Loaded PDF for feedback: \(url.lastPathComponent) (\(data.count) bytes)")
        } catch {
            MonLog_e("Failed to load PDF for feedback", error: error)
        }
    }

    func submitFeedback(context: ModelContext) {
        let isPdf = originalPdfData != nil
        let defaultTitle = "Feedback: \(selectedType)"
        let title = selectedFileName ?? defaultTitle
        
        let record = HistoryRecord(
            timestamp: Date(),
            fileName: title,
            fileType: isPdf ? "application/pdf" : (sourceImage != nil ? "image/jpeg" : "text/plain"),
            text: "[\(selectedType)] \(correctedText)",
            processingTimeMs: 0,
            category: "feedback",
            imageData: originalPdfData ?? sourceImage?.jpegData(compressionQuality: 0.6)
        )

        context.insert(record)
        try? context.save()
        
        // Trigger immediate sync on background actor
        Task { await SyncService.shared.syncAll() }
        
        // Reset form
        correctedText = ""
        sourceImage = nil
        originalPdfData = nil
        selectedFileName = nil
        consent = false
    }
}
