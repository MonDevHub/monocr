import SwiftUI

struct AboutView: View {
    @Environment(\.dismiss) var dismiss
    
    @Binding var showDocs: Bool
    @Binding var showContribute: Bool
    @Binding var showFeedback: Bool
    
    private var modelInfo: [(String, String)] {
        [
            ("Architecture", "MobileNetV3 + BiLSTM-384 + CTC"),
            ("Parameters", "~6.6M"),
            ("Input", "128 × 1024 px"),
            ("Precision", "FP16 (Core ML ANE)"),
            ("Val CER", "2.79%"),
            ("Model Size", "~13 MB"),
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
                        
                        Text("Version 1.0.0 (Build 1)")
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
