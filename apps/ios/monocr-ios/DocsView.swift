import SwiftUI

struct DocsView: View {
    @Environment(\.dismiss) var dismiss
    @State private var selectedSdk = "JS"
    
    let sdks = ["JS", "Python", "Go", "Rust"]
    
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Academic-grade OCR engine for Mon script. High-performance, private, and localized.")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets(top: 8, leading: 0, bottom: 24, trailing: 0))
                }
                
                Section {
                    DocRow(number: "1", title: "Installation", systemImage: "shippingbox") {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Install the latest stable release of the MonOCR engine via our package manager.")
                                .font(MonTheme.Typography.meta)
                                .foregroundColor(.secondary)
                            CodeBlockView(code: "pip install monocr")
                        }
                    }
                }
                
                Section {
                    DocRow(number: "2", title: "CLI Usage", systemImage: "terminal") {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Use the command line interface for processing large PDFs (>50MB) or batch folders.")
                                .font(MonTheme.Typography.meta)
                                .foregroundColor(.secondary)
                            CodeBlockView(code: "monocr read image.png\nmonocr batch folder/")
                        }
                    }
                }
                
                Section {
                    DocRow(number: "3", title: "Multi-Platform SDKs", systemImage: "code.square") {
                        VStack(alignment: .leading, spacing: 16) {
                            Text("Official libraries for high-performance inference.")
                                .font(MonTheme.Typography.meta)
                                .foregroundColor(.secondary)
                            
                            VStack(spacing: 12) {
                                Picker("SDK", selection: $selectedSdk) {
                                    ForEach(sdks, id: \.self) { sdk in
                                        Text(sdk).tag(sdk)
                                    }
                                }
                                .pickerStyle(.segmented)
                                
                                CodeBlockView(code: sdkCode(for: selectedSdk))
                                    .transition(.opacity.combined(with: .scale(scale: 0.98)))
                            }
                        }
                    }
                }
                
                Section {
                    DocRow(number: "4", title: "Input Standards", systemImage: "checklist") {
                        VStack(alignment: .leading, spacing: 16) {
                            Text("Follow these standards to achieve maximum recognition accuracy (97.5%+).")
                                .font(MonTheme.Typography.meta)
                                .foregroundColor(.secondary)
                            
                            HStack(spacing: 12) {
                                QualityItem(title: "Resolution", description: "300 DPI min. 600 DPI for manuscripts.", systemImage: "photo.stack")
                                QualityItem(title: "Lighting", description: "Diffuse lighting, no glares.", systemImage: "sun.max")
                            }
                        }
                    }
                }
                
                Section {
                    VStack(alignment: .leading, spacing: 12) {
                        HStack {
                            Image(systemName: "shield.checkered")
                                .foregroundColor(.accentColor)
                            Text("Privacy-First OCR")
                            .font(MonTheme.Typography.section)
                        }
                        
                        Text("Documents are processed entirely on your local machine. No data is uploaded to our servers.")
                            .font(MonTheme.Typography.meta)
                            .foregroundColor(.secondary)
                        
                        Label("100% Local Processing", systemImage: "lock.shield")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(.accentColor)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(Color.accentColor.opacity(0.1))
                            .cornerRadius(4)
                    }
                    .padding(.vertical, 8)
                }
                
                Section("Model Hub") {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Our production weights and multi-format exports are hosted on Hugging Face.")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        
                        Link(destination: URL(string: "https://huggingface.co/janakhpon/monocr")!) {
                            HStack {
                                Text("Visit Hugging Face")
                                    .fontWeight(.medium)
                                Spacer()
                                Image(systemName: "arrow.up.right")
                            }
                            .padding()
                            .background(Color.accentColor.opacity(0.1))
                            .cornerRadius(4)
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Documentation")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
    
    private func sdkCode(for sdk: String) -> String {
        switch sdk {
        case "JS": 
            return "// 1. Install\nnpm install monocr\n\n// 2. Use\nimport { MonOCR } from 'monocr';\nconst ocr = new MonOCR();\nconst text = await ocr.predict('page.jpg');"
        case "Python": 
            return "# 1. Install\npip install monocr\n\n# 2. Use\nfrom monocr import MonOCR\nocr = MonOCR()\ntext = ocr.predict('page.jpg')"
        case "Go": 
            return "// 1. Install\ngo get github.com/MonDevHub/monocr-onnx/go\n\n// 2. Use\nimport \"ocr\"\nengine, _ := ocr.NewMonOCR(\"\")\ntext, _ := engine.Predict(\"page.jpg\")"
        case "Rust": 
            return "// 1. Install\ncargo add monocr-onnx\n\n// 2. Use\nuse monocr_onnx::MonOCR;\nlet ocr = MonOCR::new(\"monocr.onnx\")?;\nlet text = ocr.predict(\"page.jpg\")?;"
        default: return ""
        }
    }
}

struct DocRow<Content: View>: View {
    let number: String
    let title: String
    let systemImage: String
    let content: () -> Content
    
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Text(number)
                    .font(.system(size: 10, weight: .bold))
                    .frame(width: 20, height: 20)
                    .background(Color(.systemGray5))
                    .cornerRadius(4)
                
                Image(systemName: systemImage)
                    .font(.system(size: 14))
                    .foregroundColor(.secondary)
                
                Text(title)
                    .font(MonTheme.Typography.section)
            }
            
            content()
        }
        .padding(.vertical, 8)
    }
}

struct CodeBlockView: View {
    let code: String
    
    var body: some View {
        Text(code)
            .font(.system(size: 12, weight: .regular, design: .monospaced))
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemBackground))
            .cornerRadius(4)
            .overlay(
                RoundedRectangle(cornerRadius: 4)
                    .stroke(Color(.separator), lineWidth: 0.5)
            )
    }
}

struct QualityItem: View {
    let title: String
    let description: String
    let systemImage: String
    
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Image(systemName: systemImage)
                .font(.title3)
                .foregroundColor(.accentColor)
            
            Text(title)
                .font(MonTheme.Typography.meta)
                .fontWeight(.bold)
            
            Text(description)
                .font(.system(size: 10))
                .foregroundColor(.secondary)
                .lineLimit(2)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground))
        .cornerRadius(10)
    }
}
