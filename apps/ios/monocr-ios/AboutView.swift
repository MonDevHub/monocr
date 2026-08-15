import SwiftUI

struct AboutView: View {
    @Environment(\.dismiss) var dismiss
    
    @Binding var showDocs: Bool
    @Binding var showContribute: Bool
    @Binding var showFeedback: Bool
    
    // Read off the bundled artifact, not off a spec sheet. monocr.mlpackage is
    // 24,266,359 bytes on disk = 24.3 MB decimal, of which a 24,173,304-byte
    // weight.bin at FP32. The package total is the honest figure: it is what
    // ships. Decimal MB, not MiB, matching README.md and the other two apps.
    //
    // Three of these rows were wrong until 2026-08-15. Precision read FP16 and
    // size read ~13 MB, both describing a quantised export this app has never
    // shipped. "Val CER 2.79%" was worse, and it is not an invented number:
    // mon_OCR's AUDIT-2026-08.md F-07 records it as that repository's own README
    // figure, reported as a beam-decode column beside 1.52% greedy for a code
    // path that could not produce two different numbers, because beam silently
    // ran greedy. It was retracted there and went on shipping here. Removed
    // rather than replaced with the v2 checkpoint's 2.5%, which was measured on
    // a split that shared its typefaces with training.
    private static var versionString: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "—"
        let build = info?["CFBundleVersion"] as? String ?? "—"
        return "Version \(short) (Build \(build))"
    }

    private var modelInfo: [(String, String)] {
        [
            ("Architecture", "MobileNetV3 + BiLSTM-384 + CTC"),
            ("Parameters", "11.55M"),
            ("Input", "160 × 1024 px"),
            ("Precision", "FP32 (Core ML)"),
            ("Model Size", "46.3 MB"),
            ("Language", "Mon (mnw)")
        ]
    }
    
    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(spacing: 12) {
                        Image(systemName: "text.viewfinder")
                            .font(.system(size: 60))
                            .foregroundColor(.accentColor)
                            .padding(.top, 12)
                        
                        Text("MonOCR")
                            .font(MonTheme.Typography.title)
                        
                        // Read from the bundle, not typed in. This said
                        // "Version 1.0.0 (Build 1)" while the project settings
                        // said MARKETING_VERSION 1.0 — two versions for one app,
                        // and the one users saw was the one nothing updated.
                        Text(Self.versionString)
                            .font(MonTheme.Typography.meta)
                            .foregroundColor(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                }
                .listRowBackground(Color.clear)
                
                Section("Overview") {
                    Text("Privacy-first on-device OCR for the Mon language. No images leave your device — all recognition runs locally using native Apple Core ML.")
                        .font(MonTheme.Typography.body)
                        .foregroundColor(.secondary)
                        .lineSpacing(4)
                }
                
                Section("Model Information") {
                    ForEach(modelInfo, id: \.1) { item in
                        LabeledContent {
                            Text(item.1)
                                .foregroundColor(.primary)
                        } label: {
                            Text(LocalizedStringKey(item.0))
                                .font(MonTheme.Typography.body)
                        }
                    }
                }
                
                Section("Resources") {
                    NavigationLink {
                        PrivacyView()
                    } label: {
                        Label("Privacy Policy", systemImage: "lock.shield")
                    }
                    Link(destination: URL(string: "https://huggingface.co/janakhpon/monocr")!) {
                        Label("Hugging Face Models", systemImage: "cpu")
                    }
                    Link(destination: URL(string: "https://github.com/MonDevHub/monocr-web")!) {
                        Label("monocr-web (GitHub)", systemImage: "terminal")
                    }
                    Link(destination: URL(string: "https://www.npmjs.com/package/monocr")!) {
                        Label("NPM Package", systemImage: "shippingbox")
                    }
                    Link(destination: URL(string: "https://pypi.org/project/monocr-onnx/")!) {
                        Label("PyPI Package", systemImage: "shippingbox")
                    }
                }
                
                Section("Application") {
                    Button {
                        dismiss()
                        showDocs = true
                    } label: {
                        Label("Documentation", systemImage: "book")
                    }
                    
                    Button {
                        dismiss()
                        showContribute = true
                    } label: {
                        Label("Contribute", systemImage: "heart")
                    }
                    
                    Button {
                        dismiss()
                        showFeedback = true
                    } label: {
                        Label("Feedback", systemImage: "flag")
                    }
                    
                    if FileManager.default.fileExists(atPath: MonLogger.logFileURL.path) {
                        ShareLink(item: MonLogger.logFileURL) {
                            Label("Export Debug Logs", systemImage: "ladybug")
                        }
                    }
                }
                
                Section {
                    VStack(spacing: 4) {
                        Text("Crafted by MonDevHub")
                        Text("© 2026 Janakhpon · MIT License")
                    }
                    .font(MonTheme.Typography.meta)
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                }
                .listRowBackground(Color.clear)
            }
            .navigationTitle("About")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") { dismiss() }
                        .fontWeight(.semibold)
                }
            }
        }
    }
}
