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
    /// A problem to show; these failures used to reach the log only.
    @Published var message: UserMessage? = nil
    /// Set after a submit, so the screen can say what happened to it.
    @Published var submitted = false
    /// A contribution is uploaded on submit, so it waits for consent, as
    /// Feedback always has. This screen used to send on a tap.
    @Published var consent: Bool = false

    var isSubmitDisabled: Bool {
        !SubmissionRules.canSubmitContribution(
            transcription: transcription,
            hasAttachment: sourceImage != nil || originalPdfData != nil,
            consent: consent)
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
            MonLog_i("Loaded PDF document: \(url.lastPathComponent) (\(data.count) bytes)")
        } catch {
            MonLog_e("Failed to load PDF document", error: error)
            message = .pdfUnreadable
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
        submitted = true
        
        // Reset form
        transcription = ""
        sourceImage = nil
        originalPdfData = nil
        selectedFileName = nil
        consent = false
    }
}
