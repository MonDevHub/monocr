import Foundation
import SwiftUI
import SwiftData
import Combine

@MainActor
class MainViewModel: ObservableObject {
// EngineStatus is now a top-level enum in EngineStatus.swift

    
    @Published var selectedImage: UIImage?
    @Published var debugImage: UIImage?
    @Published var ocrResult: MonOcrResult?
    @Published var isProcessing = false
    @Published var status: EngineStatus = .loading
    @Published var showCamera = false
    @Published var errorMessage: String?
    
    private let engine = MonOcrEngine()
    
    init() {
        Task {
            await initializeEngine()
        }
    }
    
    func initializeEngine() async {
        status = .loading
        errorMessage = nil
        do {
            try await engine.initialize()
            status = .ready
        } catch {
            status = .error(error.localizedDescription)
            errorMessage = "Failed to initialize OCR engine: \(error.localizedDescription)"
            MonLogger.e("Engine initialization failed", error: error)
        }
    }
    
    // MARK: - History Persistence

    /// Persist a completed scan as a HistoryRecord in SwiftData.
    /// modelContext is passed in from ContentView which has @Environment(\\.modelContext) access.
    private func saveHistory(result: MonOcrResult, fileName: String, context: ModelContext?) {
        guard let context else { return }
        
        // Convert the debug image (or original image if debug missing) to JPEG data for storage
        var imageData: Data? = nil
        if let imageToSave = result.debugImage ?? selectedImage {
            // Compress heavily for history thumbnail (0.5 quality)
            imageData = imageToSave.jpegData(compressionQuality: 0.5)
        }
        
        let record = HistoryRecord(
            fileName: fileName,
            fileType: "image/jpeg",
            text: result.text,
            processingTimeMs: result.durationMs,
            category: "scan",
            imageData: imageData
        )
        context.insert(record)
        do {
            try context.save()
        } catch {
            MonLogger.e("Failed to save history record: \(error)")
        }
    }

    // MARK: - Image Processing

    func processImage(_ image: UIImage, modelContext: ModelContext? = nil) {
        selectedImage = image
        ocrResult = nil
        isProcessing = true
        errorMessage = nil
        
        Task {
            let startTime = Date()
            do {
                let result = try await engine.recognize(image: image)
                let duration = Date().timeIntervalSince(startTime)
                MonLogger.i("Recognition took \(String(format: "%.2f", duration))s")
                
                await MainActor.run {
                    self.ocrResult = result
                    self.debugImage = result.debugImage
                    self.isProcessing = false
                    // Persist to history
                    let label = "scan_\(Int(Date().timeIntervalSince1970))"
                    self.saveHistory(result: result, fileName: label, context: modelContext)
                }
            } catch {
                await MainActor.run {
                    errorMessage = "Recognition failed: \(error.localizedDescription)"
                    status = .error(error.localizedDescription)
                    isProcessing = false
                }
            }
        }
    }

    func processPdf(at url: URL, modelContext: ModelContext? = nil) {
        if !url.startAccessingSecurityScopedResource() {
            errorMessage = "Could not access file. Please check permissions."
            status = .error("Permission denied")
            return
        }
        
        if let attr = try? FileManager.default.attributesOfItem(atPath: url.path),
           let size = attr[.size] as? Int64,
           size > 50 * 1024 * 1024 {
            errorMessage = "File too large (Max 50MB). Use CLI tools or desktop version for bigger file support."
            status = .error("File size limit exceeded")
            url.stopAccessingSecurityScopedResource()
            return
        }

        guard url.startAccessingSecurityScopedResource() else {
            errorMessage = "Failed to access PDF file."
            return
        }
        
        isProcessing = true
        ocrResult = nil
        errorMessage = nil
        
        if let previewImage = PdfUtil.renderPdfPageToImage(at: url, pageIndex: 0) {
            self.selectedImage = previewImage
        }
        
        Task {
            defer { url.stopAccessingSecurityScopedResource() }
            
            let startTime = Date()
            let totalPages = PdfUtil.getPageCount(at: url)
            MonLogger.i("Starting parallel PDF OCR for \(totalPages) pages")
            
            var resultsMap = [Int: MonOcrResult]()
            
            await withTaskGroup(of: (Int, MonOcrResult?).self) { group in
                for i in 0..<totalPages {
                    group.addTask {
                        if let image = PdfUtil.renderPdfPageToImage(at: url, pageIndex: i) {
                            do {
                                let result = try await self.engine.recognize(image: image)
                                return (i, result)
                            } catch {
                                return (i, nil)
                            }
                        }
                        return (i, nil)
                    }
                }
                
                for await (index, result) in group {
                    if let res = result {
                        resultsMap[index] = res
                        
                        let currentCombinedText = (0..<totalPages).compactMap { i -> String? in
                            guard let r = resultsMap[i] else { return nil }
                            return "--- Page \(i + 1) ---\n\(r.text)\n\n"
                        }.joined()
                        
                        let totalWords = resultsMap.values.reduce(0) { $0 + $1.wordCount }
                        let totalChars = resultsMap.values.reduce(0) { $0 + $1.charCount }
                        
                        await MainActor.run {
                            self.ocrResult = MonOcrResult(
                                text: currentCombinedText.trimmingCharacters(in: .whitespacesAndNewlines),
                                wordCount: totalWords,
                                charCount: totalChars,
                                durationMs: Int(Date().timeIntervalSince(startTime) * 1000),
                                debugImage: res.debugImage
                            )
                            self.debugImage = res.debugImage
                        }
                    }
                }
            }
            
            await MainActor.run {
                self.isProcessing = false
                // Persist final combined result to history
                if let final = self.ocrResult {
                    let pdfName = url.deletingPathExtension().lastPathComponent
                    self.saveHistory(result: final, fileName: pdfName, context: modelContext)
                }
            }
        }
    }
    
    func clearResult() {
        selectedImage = nil
        ocrResult = nil
        errorMessage = nil
        isProcessing = false
        debugImage = nil
        status = .ready
    }
}
