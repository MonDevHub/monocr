import Foundation
import UIKit
import CoreML
import Vision

/**
 OCR Errors with user-friendly descriptions.
 */
enum OcrError: LocalizedError {
    case notInitialized
    case modelNotFound(String)
    case processingError(String)
    case modelContract(String)
    case inferenceFailed(String)

    var errorDescription: String? {
        switch self {
        case .notInitialized:
            return NSLocalizedString("Engine not initialized. Please try restarting the scan.", comment: "")
        case .modelNotFound(let name):
            let format = NSLocalizedString("Model '%@' not found. Ensure .mlpackage is added to the app target in Xcode.", comment: "")
            return String(format: format, name)
        case .processingError(let msg):
            let format = NSLocalizedString("Internal Error: %@", comment: "")
            return String(format: format, msg)
        case .modelContract(let msg):
            let format = NSLocalizedString("This build cannot use the bundled model: %@", comment: "")
            return String(format: format, msg)
        case .inferenceFailed(let msg):
            let format = NSLocalizedString("Recognition failed: %@", comment: "")
            return String(format: format, msg)
        }
    }
}

/**
 Main OCR engine for Mon language.
 Migration: Now using native Core ML for better performance on ANE.
 NOTE: No external 'CoreML' package is required via SPM/CocoaPods.
 CoreML and Vision are built-in Apple frameworks.
 */
actor MonOcrEngine {

    private var charset: String = ""
    private var isInitialized = false
    private var model: MLModel?
    private var initTask: Task<Void, Error>?

    // The model all three apps ship, identified by the revision it came from rather
    // than by a date. `2026.03.21.v1` was none of: not a model generation, not a
    // Hugging Face revision, and not the date of anything checkable. It was declared
    // in three languages and read by nothing, so it drifted without consequence until
    // someone tried to use it to answer which model was deployed.
    //
    // `d3d9d5e` is the revision the web app pins and the four monocr-onnx SDKs pin.
    // Bump this in the same change that bumps those, or it stops being an answer.
    static let MODEL_VERSION = "v3.5@d3d9d5e"

    init() {
        // Actor init
    }

    /// CTC reserves index 0 for the blank, so a model over N characters emits
    /// N + 1 classes.
    private var expectedClasses: Int { charset.utf16.count + 1 }

    private func loadCharset() throws {
        guard let path = Bundle.main.path(forResource: "charset", ofType: "txt") else {
            throw OcrError.modelContract(
                NSLocalizedString("charset.txt is missing from the app bundle.", comment: "")
            )
        }

        let contents: String
        do {
            contents = try String(contentsOfFile: path, encoding: .utf8)
        } catch {
            MonLog_e("could not read charset.txt", error: error)
            throw OcrError.modelContract(
                NSLocalizedString("charset.txt could not be read.", comment: "")
            )
        }

        // A trailing newline from an editor would otherwise count as a character
        // and fail the class-count check on an otherwise correct pair of files.
        // Only the trailing end is trimmed: a leading one would shift every index.
        var trimmed = contents
        while trimmed.hasSuffix("\n") || trimmed.hasSuffix("\r") {
            trimmed.removeLast()
        }
        if trimmed.count != contents.count {
            MonLog_w("charset.txt had \(contents.count - trimmed.count) trailing newline(s); ignoring them")
        }
        guard !trimmed.isEmpty else {
            throw OcrError.modelContract(
                NSLocalizedString("charset.txt is empty.", comment: "")
            )
        }

        charset = trimmed
        MonLog_d("charset loaded: \(charset.count) characters, \(charset.utf16.count) utf16 units")
    }

    /// Initialize Core ML engine
    func initialize() async throws {
        if isInitialized { return }

        if let existingTask = initTask {
            return try await existingTask.value
        }

        let newTask = Task {
            MonLog_i("initializing Mon OCR engine (Core ML)")
            let startLoad = Date()

            try loadCharset()

            let config = MLModelConfiguration()
            config.computeUnits = .all
            config.allowLowPrecisionAccumulationOnGPU = true

            let modelExtensions = ["mlmodelc", "mlmodel"]
            var targetURL: URL?

            for ext in modelExtensions {
                if let url = Bundle.main.url(forResource: "monocr", withExtension: ext) {
                    targetURL = url
                    break
                }
            }

            guard let url = targetURL else {
                MonLog_e("model 'monocr' not found in bundle")
                throw OcrError.modelNotFound("monocr")
            }

            let loaded: MLModel
            do {
                loaded = try MLModel(contentsOf: url, configuration: config)
            } catch {
                MonLog_e("failed to load model", error: error)
                throw OcrError.processingError(error.localizedDescription)
            }

            // Before anything is decoded, not after. A contract failure here is
            // fatal on purpose; see assertModelContract.
            try self.assertModelContract(loaded)

            await self.storeInitializedModel(loaded, duration: Date().timeIntervalSince(startLoad))
        }

        self.initTask = newTask
        return try await newTask.value
    }

    /**
     Refuse to run a model that does not match what this app preprocesses and
     decodes with.

     The weights are a bundled artifact and the charset is a bundled text file, so
     nothing structurally ties the two together — they agree because someone
     checked. The failure this prevents is not a crash: a 277-class graph read
     through a 315-character table yields well-formed Mon text that is wrong,
     with no exception and no lookup miss, because every decodable index is in
     range of the larger table. There is no symptom to notice. The same is true
     of a height mismatch, which just squashes every line by a constant factor.

     Web does this in `apps/web/src/lib/monocr-onnx.ts`; this is the iOS half.
     */
    private func assertModelContract(_ model: MLModel) throws {
        let description = model.modelDescription

        guard let input = description.inputDescriptionsByName["input"] else {
            throw OcrError.modelContract(
                "the graph has no input named 'input'; this build feeds one called 'input'."
            )
        }
        guard let inputConstraint = input.multiArrayConstraint else {
            throw OcrError.modelContract("input 'input' is not a multi-array.")
        }

        let inputShape = inputConstraint.shape.map { $0.intValue }
        if inputShape.count >= 3 {
            // Height is second from the end in both [1, 1, H, W] and [1, H, W].
            let declaredHeight = inputShape[inputShape.count - 2]
            if declaredHeight <= 0 {
                // A flexible axis cannot be checked here. That is not a pass, so
                // say so out loud: a graph missing the fields a check needs is
                // disproportionately likely to be the one that is wrong.
                MonLog_w("model input height is flexible (\(declaredHeight)); cannot verify it against \(ModelWindow.height)")
            } else if declaredHeight != ModelWindow.height {
                throw OcrError.modelContract(
                    "the graph expects an input height of \(declaredHeight)px and this build "
                        + "preprocesses to \(ModelWindow.height)px, so they are different generations."
                )
            }
        } else {
            MonLog_w("model input shape \(inputShape) has no height axis to check")
        }

        guard let output = description.outputDescriptionsByName["logits"] else {
            throw OcrError.modelContract(
                "the graph has no output named 'logits'; this build reads one called 'logits'."
            )
        }
        guard let outputConstraint = output.multiArrayConstraint else {
            throw OcrError.modelContract("output 'logits' is not a multi-array.")
        }

        // The decode reinterprets the output buffer as Float32, so a float16
        // graph would be read as noise. Warned about here and enforced in
        // runInference on the tensor that actually comes back, which is the one
        // that gets reinterpreted.
        if outputConstraint.dataType != .float32 {
            MonLog_w("model declares 'logits' as \(outputConstraint.dataType.rawValue), not float32")
        }

        let outputShape = outputConstraint.shape.map { $0.intValue }
        if outputShape.contains(where: { $0 <= 0 }) {
            // Recoverable: runInference resolves the axes again on the tensor
            // that actually comes back, and throws there. Deferred, not skipped.
            MonLog_w("model output shape \(outputShape) is flexible; deferring the charset contract check to the first decode")
        } else if LogitsLayout.resolve(shape: outputShape, expectedClasses: expectedClasses) == nil {
            throw OcrError.modelContract(
                "the graph emits \(outputShape) and no axis has the \(expectedClasses) classes the "
                    + "bundled charset needs (one CTC blank plus one per its \(charset.utf16.count) "
                    + "characters). Refusing to decode."
            )
        }

        MonLog_i("model contract checked: input \(inputShape), output \(outputShape), \(expectedClasses) classes")
    }

    private func storeInitializedModel(_ model: MLModel, duration: TimeInterval) async {
        self.model = model
        self.isInitialized = true
        MonLog_i("engine initialized in \(String(format: "%.2f", duration))s")
    }

    /**
     Run OCR on a whole image.

     `mode` decides how the page is cut into lines; see `SegmentationMode`. Every
     failure below is thrown rather than turned into empty text, because a page
     that failed and a page that is genuinely blank look identical once they are
     both "".
     */
    func recognize(image: UIImage, mode: SegmentationMode) async throws -> MonOcrResult {
        let startTime = Date()
        MonLog_i("starting recognition: \(Int(image.size.width))x\(Int(image.size.height)) mode=\(mode.rawValue)")

        if !isInitialized {
            try await initialize()
        }

        guard let raw = GreyImage.upright(image) else {
            throw OcrError.processingError(
                NSLocalizedString("Could not read the image pixels.", comment: "")
            )
        }

        // Polarity and background are settled here, once, BEFORE segmentation.
        // The projection profile treats dark pixels as ink and cannot tell that
        // it was handed an inverted scan, so on a dark-mode screenshot it used to
        // return the gaps between lines as the lines.
        let page = PageNormalizer.normalize(raw)
        guard let pageImage = page.makeUIImage() else {
            throw OcrError.processingError(
                NSLocalizedString("Could not prepare the page for reading.", comment: "")
            )
        }

        let wholePage = LineSegment(x: 0, y: 0, width: page.width, height: page.height)
        var bands: [LineSegment]
        // A mode with no ratio never runs the profile, so the absence of one IS the
        // branch. This compared against `.line` while the ratio was non-optional,
        // which meant the two could drift: a new mode that should skip segmenting
        // would have been segmented anyway. `OcrRepository.performOcr` reads the
        // same way on Android.
        if let ratio = mode.densityThresholdRatio {
            bands = LineSegmenter.segment(page: page, densityThresholdRatio: ratio)
            if bands.isEmpty {
                MonLog_i("no lines found; reading the whole page as one line")
                bands = [wholePage]
            }
        } else {
            bands = [wholePage]
        }
        MonLog_d("segmented into \(bands.count) bands")

        // Tile any band too wide for the window instead of squeezing it into one.
        let tiledBands = bands.map { band in
            LineTiler.tileLine(
                page: page,
                segment: band,
                targetHeight: ModelWindow.height,
                targetWidth: ModelWindow.width
            )
        }
        let tileCount = tiledBands.reduce(0) { $0 + $1.count }
        if tileCount > bands.count {
            MonLog_i("tiled \(bands.count) bands into \(tileCount) model windows")
        }

        var pieces = [(band: Int, tile: Int, text: String)]()
        pieces.reserveCapacity(tileCount)

        // Preprocessing runs INSIDE the task, and only a few tasks exist at once.
        // It used to run in this submission loop instead: the loop had no await in
        // it and runInference is actor-isolated, so every tile of the page was
        // turned into a 1024x160 [Float] — 640 KiB — before the first inference
        // started, and all of them stayed alive until the group drained. A dense
        // A4 scan of 60 bands is ~38 MiB of buffers to read four at a time.
        //
        // Capped low because Core ML serialises on the ANE anyway, so more
        // in-flight tiles buy no throughput and only cost the buffers.
        let inFlight = max(2, min(4, ProcessInfo.processInfo.activeProcessorCount))

        try await withThrowingTaskGroup(of: (Int, Int, String).self) { group in
            var submitted = 0
            for (bandIndex, tiles) in tiledBands.enumerated() {
                for (tileIndex, tile) in tiles.enumerated() {
                    // Wait for a slot before adding, so the group never holds more
                    // than `inFlight` buffers. next() rethrows, which keeps the
                    // old failure behaviour: the first error cancels the group.
                    if submitted >= inFlight, let piece = try await group.next() {
                        pieces.append((band: piece.0, tile: piece.1, text: piece.2))
                    }
                    group.addTask {
                        guard let tileData = ImagePreprocessor.processLine(source: pageImage, segment: tile) else {
                            throw OcrError.processingError(
                                "could not prepare band \(bandIndex + 1) tile \(tileIndex + 1) for the model"
                            )
                        }
                        let text = try await self.runInference(lineData: tileData)
                        return (bandIndex, tileIndex, text)
                    }
                    submitted += 1
                }
            }

            for try await piece in group {
                pieces.append((band: piece.0, tile: piece.1, text: piece.2))
            }
        }

        // Tiles of one band join with NO separator: the cut falls inside a word,
        // so a space there would be wrong. Distinct bands join with a newline.
        var lines = [RecognizedLine]()
        for (bandIndex, band) in bands.enumerated() {
            let text = pieces
                .filter { $0.band == bandIndex }
                .sorted { $0.tile < $1.tile }
                .map { $0.text }
                .joined()
            lines.append(
                RecognizedLine(
                    text: text,
                    bbox: band,
                    tileCount: tiledBands[bandIndex].count,
                    looksLikeALine: LineSegmenter.looksLikeALine(bbox: band, pageHeight: page.height)
                )
            )
        }

        let combinedText = lines.map { $0.text }.filter { !$0.isEmpty }.joined(separator: "\n")
        let totalDuration = Int(Date().timeIntervalSince(startTime) * 1000)
        let words = combinedText.trimmingCharacters(in: .whitespacesAndNewlines)
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }.count

        let blockShaped = lines.filter { !$0.looksLikeALine }.count
        if blockShaped > 0 {
            MonLog_w("\(blockShaped) of \(lines.count) bands are block-shaped, not line-shaped: their text may be invented")
        }
        MonLog_i("ocr completed: chars=\(combinedText.count) lines=\(lines.count) tiles=\(tileCount)")

        // Whichever tile finished preprocessing last, not the last tile of the
        // page: preprocessing is concurrent now. The static is lock-guarded so
        // this is a well-defined value, just an arbitrary one. It is only ever
        // shown in the debug panel, so that is left as is rather than plumbed
        // through the task group.
        let debugImg = ImagePreprocessor.lastProcessedLineImage

        return MonOcrResult(
            text: combinedText,
            wordCount: words,
            charCount: combinedText.count,
            durationMs: totalDuration,
            debugImage: debugImg,
            lines: lines,
            mode: mode
        )
    }

    /// Run inference on a single line of float data using Core ML
    private func runInference(lineData: [Float]) async throws -> String {
        guard let model = model else { throw OcrError.notInitialized }

        // A failure below used to return "" per line, which made a broken model
        // and a blank page indistinguishable on screen. Every path now throws.
        let outputFeatures: MLFeatureProvider
        do {
            let width = lineData.count / ModelWindow.height
            let shape: [NSNumber] = [1, 1, NSNumber(value: ModelWindow.height), NSNumber(value: width)]

            let inputArray = try MLMultiArray(shape: shape, dataType: .float32)
            inputArray.withUnsafeMutableBufferPointer(ofType: Float.self) { ptr, _ in
                for i in 0..<lineData.count {
                    ptr[i] = lineData[i]
                }
            }

            let inputProvider = try MLDictionaryFeatureProvider(dictionary: ["input": inputArray])
            outputFeatures = try await model.prediction(from: inputProvider)
        } catch {
            MonLog_e("core ml inference error", error: error)
            throw OcrError.inferenceFailed(error.localizedDescription)
        }

        guard let logitsArray = outputFeatures.featureValue(for: "logits")?.multiArrayValue else {
            throw OcrError.inferenceFailed("the model returned no 'logits' output")
        }

        // The copy below reinterprets the buffer as Float32; any other element
        // type would decode as noise rather than fail.
        guard logitsArray.dataType == .float32 else {
            throw OcrError.modelContract(
                "the graph returned 'logits' as data type \(logitsArray.dataType.rawValue), and this "
                    + "build reads the tensor as float32."
            )
        }

        let logitsShape = logitsArray.shape.map { $0.intValue }
        guard let axes = LogitsLayout.resolve(shape: logitsShape, expectedClasses: expectedClasses) else {
            // Only reachable when the load-time check was deferred on a flexible
            // shape, so it has to be fatal here instead.
            throw OcrError.modelContract(
                "the graph returned \(logitsShape) and no axis has the \(expectedClasses) classes "
                    + "the bundled charset needs."
            )
        }

        let timeSteps = logitsShape[axes.timeAxis]
        let numClasses = logitsShape[axes.classAxis]

        var logits = [Float](repeating: 0, count: timeSteps * numClasses)
        let strides = logitsArray.strides.map { $0.intValue }

        var copied = false
        logitsArray.withUnsafeBufferPointer(ofType: Float.self) { ptr in
            guard let basePtr = ptr.baseAddress else { return }
            for t in 0..<timeSteps {
                let tOffset = t * strides[axes.timeAxis]
                let rowStart = t * numClasses
                for c in 0..<numClasses {
                    let cOffset = c * strides[axes.classAxis]
                    logits[rowStart + c] = basePtr[tOffset + cOffset]
                }
            }
            copied = true
        }
        // An empty buffer would otherwise decode as all-blank, which reads as a
        // blank line rather than as the failure it is.
        guard copied else {
            throw OcrError.inferenceFailed("the logits tensor had no readable buffer")
        }

        return try CtcDecoder.decode(
            logits: logits,
            timeSteps: timeSteps,
            numClasses: numClasses,
            charset: charset
        )
    }
}
