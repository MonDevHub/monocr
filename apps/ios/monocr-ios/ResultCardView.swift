import SwiftUI

struct ResultCardView: View {
    let result: MonOcrResult
    @Binding var showCopyToast: Bool
    var onReport: (() -> Void)? = nil
    
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Header
            HStack(alignment: .center) {
                Text("Extracted Text")
                    .font(MonTheme.Typography.section)

                Spacer()

                HStack(spacing: 4) {
                    StatChip(label: "\(result.wordCount) w")
                    StatChip(label: "\(result.charCount) ch")
                    StatChip(label: "\(result.durationMs)ms")
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Color(.systemGroupedBackground))

            Divider()

            // Content
            MonTextView(text: result.text)
                .frame(height: 300) // Reduced from 350 for density
                .background(Color(.systemBackground))

            Divider()

            // Footer Actions: Save / Share / Copy — matches Android toolbar
            HStack(spacing: 0) {
                Spacer()
                // Save
                Button {
                    saveTextToFile(result.text)
                } label: {
                        Label("Save", systemImage: "arrow.down.circle")
                            .font(MonTheme.Typography.meta)
                        .fontWeight(.medium)
                }
                .padding(.trailing, 12)

                // Share
                ShareLink(item: result.text) {
                    Label("Share", systemImage: "square.and.arrow.up")
                        .font(MonTheme.Typography.meta)
                        .fontWeight(.medium)
                }
                .padding(.trailing, 12)

                // Copy
                Button {
                    let generator = UIImpactFeedbackGenerator(style: .medium)
                    generator.prepare()
                    
                    UIPasteboard.general.string = result.text
                    generator.impactOccurred()
                    
                    withAnimation { showCopyToast = true }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                        withAnimation { showCopyToast = false }
                    }
                } label: {
                    Label(showCopyToast ? "Copied" : "Copy",
                          systemImage: showCopyToast ? "checkmark" : "doc.on.doc")
                        .font(MonTheme.Typography.meta)
                        .fontWeight(.medium)
                        .foregroundColor(showCopyToast ? .green : .accentColor)
                }
                
                // Report Issue
                if let onReport = onReport {
                    Button {
                        onReport()
                    } label: {
                        Label("Report", systemImage: "info.circle")
                            .font(MonTheme.Typography.meta)
                            .fontWeight(.medium)
                    }
                    .padding(.leading, 8)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8) // Reduced from 12
            .background(Color(.systemGroupedBackground).opacity(0.6))
        }
        .background(Color(.secondarySystemBackground))
        .cornerRadius(8) // Reduced from 16
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(Color(.separator), lineWidth: 0.5)
        )
    }
    
    private func StatChip(label: String) -> some View {
        Text(label)
            .font(.system(size: 11, weight: .medium, design: .monospaced))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Color(.tertiarySystemFill))
            .cornerRadius(4) // Reduced from 10
            .foregroundColor(.secondary)
    }
    
    private func saveTextToFile(_ text: String) {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd_HH_mm_ss"
        let filename = "monocr-\(formatter.string(from: Date())).txt"
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(filename)
        do {
            try text.write(to: url, atomically: true, encoding: .utf8)
            let av = UIActivityViewController(activityItems: [url], applicationActivities: nil)
            if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
               let root = scene.windows.first?.rootViewController {
                root.present(av, animated: true)
            }
        } catch {
            MonLogger.e("Failed to save text file: \(error)")
        }
    }
}
