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
    // `a51be11` is the revision the web app pins and the four monocr-onnx SDKs pin.
    // Bump this in the same change that bumps those, or it stops being an answer.
    static let MODEL_VERSION = "v3.5@d3d9d5e"
    
    init() {
        // Actor init
    }
    
    private func loadCharset() {
        if let path = Bundle.main.path(forResource: "charset", ofType: "txt") {
            do {
                charset = try String(contentsOfFile: path, encoding: .utf8)
                MonLog_d("Charset loaded: \(charset.count) characters")
            } catch {
                MonLog_e("Error loading charset", error: error)
            }
        } else {
            MonLog_w("charset.txt not found in bundle")
        }
    }
    
    /// Initialize Core ML engine
    func initialize() async throws {
        if isInitialized { return }
        
        if let existingTask = initTask {
            return try await existingTask.value
        }
        
        let newTask = Task {
            MonLog_i("🚀 Initializing Mon OCR Engine (Core ML)...")
            let startLoad = Date()
            
            loadCharset()
            
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
                MonLog_e("❌ Model 'monocr' not found in bundle.")
                throw OcrError.modelNotFound("monocr")
            }
            
            do {
                let model = try MLModel(contentsOf: url, configuration: config)
                await self.storeInitializedModel(model, duration: Date().timeIntervalSince(startLoad))
            } catch {
                MonLog_e("Failed to load model", error: error)
                throw OcrError.processingError(error.localizedDescription)
            }
        }
        
        self.initTask = newTask
        return try await newTask.value
    }
    
    private func storeInitializedModel(_ model: MLModel, duration: TimeInterval) async {
        self.model = model
        self.isInitialized = true
        MonLog_i("Engine initialized in \(String(format: "%.2f", duration))s")
    }
    
    /// Run OCR on the entire image
    func recognize(image: UIImage) async throws -> MonOcrResult {
        let startTime = Date()
        MonLog_i("Starting recognition for image: \(Int(image.size.width))x\(Int(image.size.height))")
        
        if !isInitialized {
            try await initialize()
        }
        
        guard let normalizedImage = normalize(image: image) else {
            MonLog_e("Failed to normalize image")
            return MonOcrResult(text: "", wordCount: 0, charCount: 0, durationMs: 0, debugImage: nil)
        }
        
        let segments = LineSegmenter.segment(image: normalizedImage)
        MonLog_d("Segmented into \(segments.count) lines")
        
        var finalSegments = segments
        if finalSegments.isEmpty {
            MonLog_i("No segments found, falling back to full image")
            let pixelWidth = Int(normalizedImage.size.width * normalizedImage.scale)
            let pixelHeight = Int(normalizedImage.size.height * normalizedImage.scale)
            finalSegments = [LineSegment(x: 0, y: 0, width: pixelWidth, height: pixelHeight)]
        }
        
        // Process segments in parallel
        let lineTexts = await withTaskGroup(of: (Int, String).self) { group in
            for (index, segment) in finalSegments.enumerated() {
                if let lineData = ImagePreprocessor.processLine(source: normalizedImage, segment: segment) {
                    group.addTask {
                        let text = await self.runInference(lineData: lineData)
                        return (index, text)
                    }
                } else {
                    MonLog_w("Failed to preprocess data for line \(index + 1)")
                }
            }
            
            var collected = [(Int, String)]()
            for await result in group {
                collected.append(result)
            }
            return collected.sorted { $0.0 < $1.0 }.map { $0.1 }
        }
        
        let combinedText = lineTexts.filter { !$0.isEmpty }.joined(separator: "\n")
        let totalDuration = Int(Date().timeIntervalSince(startTime) * 1000)
        let words = combinedText.trimmingCharacters(in: .whitespacesAndNewlines)
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }.count
            
        MonLog_i("OCR completed. Character count: \(combinedText.count)")
        
        // For debugging, we can return the last processed line image
        let debugImg = ImagePreprocessor.lastProcessedLineImage
        
        return MonOcrResult(
            text: combinedText,
            wordCount: words,
            charCount: combinedText.count,
            durationMs: totalDuration,
            debugImage: debugImg
        )
    }
    
    /// Run inference on a single line of float data using Core ML
    private func runInference(lineData: [Float]) async -> String {
        guard let model = model else { return "" }
        
        do {
            let width = lineData.count / 160
            let shape: [NSNumber] = [1, 1, 160, NSNumber(value: width)]
            
            let inputArray = try MLMultiArray(shape: shape, dataType: .float32)
            inputArray.withUnsafeMutableBufferPointer(ofType: Float.self) { ptr, _ in
                for i in 0..<lineData.count {
                    ptr[i] = lineData[i]
                }
            }
            
            let inputProvider = try MLDictionaryFeatureProvider(dictionary: ["input": inputArray])
            let outputFeatures = try await model.prediction(from: inputProvider)
            
            guard let logitsArray = outputFeatures.featureValue(for: "logits")?.multiArrayValue else {
                return ""
            }
            
            let logitsShape = logitsArray.shape
            let rank = logitsShape.count
            let expectedClasses = charset.utf16.count + 1
            
            var cIdx = -1
            var tIdx = -1
            
            for i in 0..<rank {
                if logitsShape[i].intValue == expectedClasses {
                    cIdx = i
                    break
                }
            }
            
            var maxDim = -1
            for i in 0..<rank {
                if i == cIdx { continue }
                let dim = logitsShape[i].intValue
                if dim > maxDim {
                    maxDim = dim
                    tIdx = i
                }
            }
            
            if tIdx == -1 {
                for i in 0..<rank {
                    if i != cIdx { tIdx = i }
                }
            }
            
            guard cIdx != -1, tIdx != -1 else { return "" }
            
            let timeSteps = logitsShape[tIdx].intValue
            let numClasses = logitsShape[cIdx].intValue
            
            var logits = [Float](repeating: 0, count: timeSteps * numClasses)
            let strides = logitsArray.strides.map { $0.intValue }
            
            logitsArray.withUnsafeBufferPointer(ofType: Float.self) { ptr in
                guard let basePtr = ptr.baseAddress else { return }
                for t in 0..<timeSteps {
                    let tOffset = t * strides[tIdx]
                    let rowStart = t * numClasses
                    for c in 0..<numClasses {
                        let cOffset = c * strides[cIdx]
                        logits[rowStart + c] = basePtr[tOffset + cOffset]
                    }
                }
            }
            
            return CtcDecoder.decode(
                logits: logits,
                timeSteps: timeSteps,
                numClasses: numClasses,
                charset: charset
            )
        } catch {
            MonLog_e("Core ML Inference error", error: error)
            return ""
        }
    }
    
    private func normalize(image: UIImage) -> UIImage? {
        let width = image.size.width * image.scale
        let height = image.size.height * image.scale
        let pixelSize = CGSize(width: width, height: height)
        
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0
        format.opaque = true
        
        let renderer = UIGraphicsImageRenderer(size: pixelSize, format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: pixelSize))
        }
    }
}
