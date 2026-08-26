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
        lastImage = image
        selectedImage = image
        ocrResult = nil
        isProcessing = true
        errorMessage = nil

        let mode = segmentationMode
        Task {
            let startTime = Date()
            do {
                let result = try await engine.recognize(image: image, mode: mode)
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
        // A PDF render is a clean page image, so the dense-text threshold is the
        // right default. The user can still switch, which re-runs this file.
        segmentationMode = ImageProvenance.pdfRender.defaultMode(pixelWidth: 0, pixelHeight: 0)
        lastImage = nil
        runPdf(at: url, modelContext: modelContext)
    }

    private func runPdf(at url: URL, modelContext: ModelContext?) {
        lastPdfURL = url

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
        
        let mode = segmentationMode
        Task {
            defer { url.stopAccessingSecurityScopedResource() }

            let startTime = Date()
            let totalPages = PdfUtil.getPageCount(at: url)
            MonLogger.i("starting pdf ocr: pages=\(totalPages) mode=\(mode.rawValue)")

            var resultsMap = [Int: MonOcrResult]()
            var failures = [Int: String]()

            // At most `inFlight` pages rendered at once. Every page here is a
            // scale-4.0 render — roughly 2380x3368, ~32 MB of bitmap — and
            // recognize() is actor-isolated, so an unbounded group rendered every
            // page before the first one was read: ~640 MB live for a 20-page PDF,
            // on a device that gets jetsammed well below that.
            //
            // Capped low because the engine is a serial actor: extra rendered
            // pages just queue, so they cost memory and buy nothing.
            let inFlight = max(2, min(4, ProcessInfo.processInfo.activeProcessorCount))

            await withTaskGroup(of: (Int, MonOcrResult?, String?).self) { group in
                // A local function because the group is now drained in two places:
                // inside the submission loop, which is what bounds the bitmaps, and
                // after it for the tail. Results stay keyed by page index, so the
                // text below is assembled in page order however they arrive.
                func record(_ index: Int, _ result: MonOcrResult?, _ failure: String?) async {
                    // A page that failed used to vanish from the output, so a
                    // half-read PDF looked like a complete one. Count them.
                    if let failure {
                        failures[index] = failure
                        MonLogger.e("pdf page failed: page=\(index + 1) reason=\(failure)")
                        return
                    }
                    guard let res = result else { return }
                    resultsMap[index] = res

                    let currentCombinedText = (0..<totalPages).compactMap { i -> String? in
                        guard let r = resultsMap[i] else { return nil }
                        return "--- Page \(i + 1) ---\n\(r.text)\n\n"
                    }.joined()

                    let totalWords = resultsMap.values.reduce(0) { $0 + $1.wordCount }
                    let totalChars = resultsMap.values.reduce(0) { $0 + $1.charCount }
                    let allLines = (0..<totalPages).flatMap { resultsMap[$0]?.lines ?? [] }

                    await MainActor.run {
                        self.ocrResult = MonOcrResult(
                            text: currentCombinedText.trimmingCharacters(in: .whitespacesAndNewlines),
                            wordCount: totalWords,
                            charCount: totalChars,
                            durationMs: Int(Date().timeIntervalSince(startTime) * 1000),
                            debugImage: res.debugImage,
                            lines: allLines,
                            mode: mode
                        )
                        self.debugImage = res.debugImage
                    }
                }

                var submitted = 0
                for i in 0..<totalPages {
                    // Wait for a slot before adding, so at most `inFlight` renders
                    // are alive. Nothing here can throw, so the whole PDF is still
                    // attempted and per-page failures are still collected.
                    if submitted >= inFlight, let piece = await group.next() {
                        await record(piece.0, piece.1, piece.2)
                    }
                    group.addTask {
                        guard let image = PdfUtil.renderPdfPageToImage(at: url, pageIndex: i) else {
                            return (i, nil, "the page could not be rendered")
                        }
                        do {
                            let result = try await self.engine.recognize(image: image, mode: mode)
                            return (i, result, nil)
                        } catch {
                            return (i, nil, error.localizedDescription)
                        }
                    }
                    submitted += 1
                }

                for await (index, result, failure) in group {
                    await record(index, result, failure)
                }
            }

            await MainActor.run {
                self.isProcessing = false
                if let firstFailure = failures.sorted(by: { $0.key < $1.key }).first {
                    self.errorMessage = String(
                        format: NSLocalizedString(
                            "%1$d of %2$d pages could not be read (page %3$d: %4$@).",
                            comment: "PDF partial failure"
                        ),
                        failures.count, totalPages, firstFailure.key + 1, firstFailure.value
                    )
                }
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
        lastImage = nil
        lastPdfURL = nil
    }
}
