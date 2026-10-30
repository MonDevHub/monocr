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
    /// A problem to show; these failures used to reach the log only.
    @Published var message: UserMessage? = nil
    /// Set after a submit, so the screen can say what happened to it.
    @Published var submitted = false
    
    let errorTypes = ["Spelling", "Layout", "Formatting", "Other"]
    
    init(originalText: String, initialImage: UIImage? = nil) {
        self.originalText = originalText
        self.correctedText = originalText
        self.sourceImage = initialImage
    }
    
    var isSubmitDisabled: Bool {
        !SubmissionRules.canSubmitFeedback(correctedText: correctedText, consent: consent)
    }

    func handleSelectedPDF(at url: URL) {
        guard url.startAccessingSecurityScopedResource() else {
            message = .pdfUnreadable
            return
        }
        defer { url.stopAccessingSecurityScopedResource() }
        
        do {
            let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
            if let fileSize = attributes[.size] as? Int64, SubmissionRules.attachmentTooLarge(byteCount: fileSize) {
                MonLog_w("PDF file too large for this version (max 20MB): \(url.lastPathComponent)")
                message = .attachmentTooLarge
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
            message = .pdfUnreadable
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
        submitted = true
        
        // Reset form
        correctedText = ""
        sourceImage = nil
        originalPdfData = nil
        selectedFileName = nil
        consent = false
    }
}
