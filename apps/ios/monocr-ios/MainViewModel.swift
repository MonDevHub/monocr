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

    /// How the next scan will cut the page into lines. Set from provenance when
    /// an image arrives, and overridable by the user.
    @Published private(set) var segmentationMode: SegmentationMode = .page

    private let engine = MonOcrEngine()
    private var jobGeneration = OcrJobGeneration()
    private var jobTask: Task<Void, Never>?
    private var activeHistoryRecord: HistoryRecord?
    private var activeFileName = "scan"


    // Kept so changing the segmentation mode can re-read what is on screen
    // instead of asking the user to pick the file again.
    private var lastImage: UIImage?
    private var lastPdfURL: URL?

    init() {
        Task {
            await initializeEngine()
        }
    }
    
    func initializeEngine() async {
        let generation = jobGeneration.current
        status = .loading
        errorMessage = nil
        do {
            try await engine.initialize()
            guard jobGeneration.accepts(generation), !isProcessing else { return }
            status = .ready
        } catch {
            guard jobGeneration.accepts(generation), !isProcessing else { return }
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
        
        // Keep the source preview, never an arbitrary processed model tile.
        let record: HistoryRecord
        if let existing = activeHistoryRecord {
            record = existing
            record.text = result.text
            record.processingTimeMs = result.durationMs
        } else {
            let imageData = selectedImage?.jpegData(compressionQuality: 0.5)
            record = HistoryRecord(fileName: fileName, fileType: "image/jpeg", text: result.text,
                processingTimeMs: result.durationMs, category: "scan", imageData: imageData)
            context.insert(record)
            activeHistoryRecord = record
        }
        record.rawText = result.rawText
        record.warningSummary = result.warningSummary
        record.ocrMetadata = result.reviewMetadata.encoded()
        do { try context.save() }
        catch { MonLogger.e("Failed to save history record: \(error)") }
    }

    private func beginJob(fileName: String, modelContext: ModelContext?) -> UUID {
        cancelProcessing(modelContext: modelContext)
        let generation = jobGeneration.advance()
        activeHistoryRecord = nil
        activeFileName = fileName
        ocrResult = nil
        debugImage = nil
        selectedImage = nil
        isProcessing = true
        errorMessage = nil
        return generation
    }

    private func canPublish(_ generation: UUID) -> Bool {
        jobGeneration.accepts(generation) && !Task.isCancelled
    }

    func cancelProcessing(modelContext: ModelContext? = nil) {
        // Backgrounding before a scan starts must not invalidate engine startup
        // and strand its status at loading.
        guard isProcessing || jobTask != nil else { return }
        let wasProcessing = isProcessing
        _ = jobGeneration.advance()
        jobTask?.cancel()
        jobTask = nil
        isProcessing = false
        status = .ready
        if wasProcessing, let partial = ocrResult {
            let cancelled = MonOcrResult(text: partial.text, wordCount: partial.wordCount,
                charCount: partial.charCount, durationMs: partial.durationMs,
                debugImage: partial.debugImage, lines: partial.lines, mode: partial.mode,
                looksSoft: partial.looksSoft, rawText: partial.rawText,
                pages: partial.pages.map {
                    PageOutcome(pageIndex: $0.pageIndex,
                        state: $0.state == .notAttempted ? .cancelled : $0.state, error: $0.error)
                }, cancelled: true)
            ocrResult = cancelled
            saveHistory(result: cancelled, fileName: activeFileName, context: modelContext)
        }
        if wasProcessing { errorMessage = "Processing cancelled. Completed pages remain in history." }
    }

    // MARK: - Image Processing

    /// Change the mode and re-read what is already on screen. A mode the user
    /// cannot see the effect of is not a control, so this re-runs rather than
    /// waiting for the next import.
    func selectSegmentationMode(_ mode: SegmentationMode, modelContext: ModelContext? = nil) {
        guard mode != segmentationMode else { return }
        segmentationMode = mode
        MonLogger.i("segmentation mode set to \(mode.rawValue)")

        // runImage / runPdf rather than processImage / processPdf: those reset the
        // mode from provenance, which would discard the choice just made.
        if let image = lastImage {
            runImage(image, modelContext: modelContext)
        } else if let url = lastPdfURL {
            runPdf(at: url, modelContext: modelContext)
        }
    }

    func processImage(
        _ image: UIImage,
        provenance: ImageProvenance,
        modelContext: ModelContext? = nil
    ) {
        let pixelWidth = Int(image.size.width * image.scale)
        let pixelHeight = Int(image.size.height * image.scale)
        segmentationMode = provenance.defaultMode(pixelWidth: pixelWidth, pixelHeight: pixelHeight)
        MonLogger.i(
            "image from \(provenance) is \(pixelWidth)x\(pixelHeight); "
                + "mode=\(segmentationMode.rawValue)"
        )

        lastPdfURL = nil
        runImage(image, modelContext: modelContext)
    }

    private func runImage(_ image: UIImage, modelContext: ModelContext?) {
        let generation = beginJob(fileName: "scan_\(Int(Date().timeIntervalSince1970))", modelContext: modelContext)
        lastImage = image
        selectedImage = image
        let mode = segmentationMode
        jobTask = Task {
            do {
                let result = try await engine.recognize(image: image, mode: mode)
                guard canPublish(generation) else { return }
                ocrResult = result
                debugImage = result.debugImage
                isProcessing = false
                status = .ready
                jobTask = nil
                saveHistory(result: result, fileName: activeFileName, context: modelContext)
            } catch {
                guard canPublish(generation) else { return }
                errorMessage = "Recognition failed: \(error.localizedDescription)"
                status = .error(error.localizedDescription)
                isProcessing = false
                jobTask = nil
            }
        }
    }

    func processPdf(at url: URL, modelContext: ModelContext? = nil) {
        // A PDF render is a clean page image, so the dense-text threshold is the
        // right default. The user can still switch, which re-runs this file.
        segmentationMode = ImageProvenance.pdfRender.defaultMode(pixelWidth: 0, pixelHeight: 0)
        lastImage = nil
        runPdf(at: url, modelContext: modelContext)
    }

    private func runPdf(at url: URL, modelContext: ModelContext?) {
        let generation = beginJob(fileName: url.deletingPathExtension().lastPathComponent, modelContext: modelContext)
        lastPdfURL = url
        let mode = segmentationMode
        jobTask = Task {
            guard canPublish(generation) else { return }
            let lease = ScopedResourceLease(start: { url.startAccessingSecurityScopedResource() },
                                            stop: { url.stopAccessingSecurityScopedResource() })
            defer { lease.close() }
            // App-owned URLs need no security scope. External URLs still have
            // to be readable after acquisition; a failed start is not a blank PDF.
            guard lease.acquired || FileManager.default.isReadableFile(atPath: url.path) else {
                failJob("Could not access the PDF file.", generation: generation)
                return
            }
            if let attr = try? FileManager.default.attributesOfItem(atPath: url.path),
               let size = attr[.size] as? Int64, size > 50 * 1024 * 1024 {
                failJob("File too large (Max 50MB). Use CLI tools or desktop version for bigger file support.",
                        generation: generation)
                return
            }
            let totalPages: Int
            do { totalPages = try PdfUtil.getPageCount(at: url) }
            catch {
                failJob(error.localizedDescription, generation: generation)
                return
            }
            guard canPublish(generation) else { return }
            selectedImage = PdfUtil.renderPdfPageToImage(at: url, pageIndex: 0)
            let startTime = Date()
            var results = [Int: MonOcrResult]()
            var outcomes = (0..<totalPages).map { PageOutcome(pageIndex: $0, state: .notAttempted, error: nil) }
            let inFlight = max(2, min(4, ProcessInfo.processInfo.activeProcessorCount))

            await withTaskGroup(of: (Int, MonOcrResult?, PageOutcome).self) { group in
                @MainActor func record(_ index: Int, _ result: MonOcrResult?, _ outcome: PageOutcome) {
                    guard canPublish(generation) else { return }
                    outcomes[index] = outcome
                    if let result { results[index] = result }
                    let readings = results.mapValues {
                        PageReading(text: $0.text, wordCount: $0.wordCount, charCount: $0.charCount,
                            lines: $0.lines, looksSoft: $0.looksSoft, rawText: $0.rawText)
                    }
                    let combined = PdfPageCombiner.combine(readings: readings, totalPages: totalPages)
                    let reading = MonOcrResult(text: combined.text, wordCount: combined.wordCount,
                        charCount: combined.charCount, durationMs: Int(Date().timeIntervalSince(startTime) * 1000),
                        debugImage: result?.debugImage, lines: combined.lines, mode: mode,
                        looksSoft: combined.looksSoft, rawText: combined.rawText, pages: outcomes)
                    ocrResult = reading
                    debugImage = reading.debugImage
                    // Upsert one history row after each page. Interruption keeps
                    // completed pages and explicit not-attempted/failed indices.
                    saveHistory(result: reading, fileName: activeFileName, context: modelContext)
                }

                var submitted = 0
                for index in 0..<totalPages {
                    guard canPublish(generation) else { group.cancelAll(); break }
                    if submitted >= inFlight, let piece = await group.next() {
                        record(piece.0, piece.1, piece.2)
                    }
                    guard canPublish(generation) else { group.cancelAll(); break }
                    group.addTask {
                        guard !Task.isCancelled else {
                            return (index, nil, PageOutcome(pageIndex: index, state: .cancelled, error: nil))
                        }
                        guard let image = PdfUtil.renderPdfPageToImage(at: url, pageIndex: index) else {
                            return (index, nil, PageOutcome(pageIndex: index, state: .renderFailed,
                                                          error: "The page could not be rendered."))
                        }
                        do {
                            try Task.checkCancellation()
                            let result = try await self.engine.recognize(image: image, mode: mode)
                            try Task.checkCancellation()
                            return (index, result, PageOutcome(pageIndex: index,
                                state: result.pages.first?.state ?? .completed, error: result.pages.first?.error))
                        } catch is CancellationError {
                            return (index, nil, PageOutcome(pageIndex: index, state: .cancelled, error: nil))
                        } catch {
                            return (index, nil, PageOutcome(pageIndex: index, state: .inferenceFailed,
                                                          error: error.localizedDescription))
                        }
                    }
                    submitted += 1
                }
                for await piece in group { record(piece.0, piece.1, piece.2) }
            }
            guard canPublish(generation) else { return }
            isProcessing = false
            status = .ready
            jobTask = nil
        }
    }

    private func failJob(_ message: String, generation: UUID) {
        guard canPublish(generation) else { return }
        errorMessage = message
        status = .error(message)
        isProcessing = false
        jobTask = nil
    }

    func clearResult(modelContext: ModelContext? = nil) {
        cancelProcessing(modelContext: modelContext)
        selectedImage = nil
        ocrResult = nil
        errorMessage = nil
        debugImage = nil
        status = .ready
        lastImage = nil
        lastPdfURL = nil
        activeHistoryRecord = nil
    }
}
