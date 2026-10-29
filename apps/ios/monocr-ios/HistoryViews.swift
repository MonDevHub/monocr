import SwiftUI
import SwiftData

struct HistorySection: View {
    let title: LocalizedStringKey
    let category: String
    @Query private var history: [HistoryRecord]
    @Environment(\.modelContext) private var modelContext
    @State private var selectedRecord: HistoryRecord?
    
    @State private var showingClearConfirmation = false
    
    init(title: LocalizedStringKey, category: String) {
        self.title = title
        self.category = category
        let filter = #Predicate<HistoryRecord> { $0.category == category }
        _history = Query(filter: filter, sort: \HistoryRecord.timestamp, order: .reverse)
    }
    
    var body: some View {
        VStack(alignment: .leading, spacing: 10) { // Reduced from 12
            HStack {
                Label(title, systemImage: "clock.arrow.circlepath")
                    .font(MonTheme.Typography.section)
                
                Spacer()
                
                if !history.isEmpty {
                    Button("Clear All") {
                        showingClearConfirmation = true
                    }
                    .font(MonTheme.Typography.meta)
                    .foregroundColor(.secondary)
                    .frame(minHeight: 44)
                    .confirmationDialog("Are you sure?", isPresented: $showingClearConfirmation) {
                        Button("Clear All History", role: .destructive) {
                            clearAll()
                        }
                    } message: {
                        Text("This will permanently delete all scans in this category.")
                    }
                }
            }
            
            if history.isEmpty {
                ContentUnavailableView {
                    Label("No history found", systemImage: "doc.text.magnifyingglass")
                }
                .frame(height: 80)
                .background(MonTheme.surfaceColor.opacity(0.4))
                .cornerRadius(4) // Reduced from 8
            } else {
                VStack(spacing: 6) { // Reduced from 8
                    ForEach(history) { record in
                        HistoryItemView(record: record) {
                            selectedRecord = record
                        }
                    }
                }
            }
        }
        .sheet(item: $selectedRecord) { record in
            HistoryDetailView(record: record)
        }
    }
    
    private func clearAll() {
        for record in history {
            modelContext.delete(record)
        }
    }
}

/// One history row: a button that opens the record, and a separate delete
/// button that asks first.
///
/// The row was a plain view with `.onTapGesture`, so VoiceOver did not call it a
/// button, and `.accessibilityElement(children: .combine)` folded the trash
/// button into it, leaving delete unreachable. The trash glyph was a 14pt
/// borderless target that deleted on touch with no way back.
struct HistoryItemView: View {
    let record: HistoryRecord
    let onOpen: () -> Void
    @Environment(\.modelContext) private var modelContext
    @State private var confirmingDelete = false

    var body: some View {
        HStack(spacing: 4) {
            Button(action: onOpen) {
                HStack(spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 6)
                            .fill(Color.accentColor.opacity(0.15))
                            .frame(width: 40, height: 40)

                        Image(systemName: "doc.text")
                            .font(.system(size: 18))
                            .foregroundColor(.accentColor)
                    }
                    .accessibilityHidden(true)

                    VStack(alignment: .leading, spacing: 4) {
                        Text(verbatim: record.fileName)
                            .font(MonTheme.Typography.meta)
                            .fontWeight(.bold)
                            .foregroundColor(.primary)
                            .lineLimit(1)

                        HStack(spacing: 6) {
                            Text(record.timestamp.formatted(.dateTime.month().day().hour().minute()))
                                .font(.system(size: 10))
                                .foregroundColor(.secondary)

                            // Only rows that are uploaded get an upload badge. Every scan
                            // row showed "Pending" with an upload cloud, for a scan that
                            // never leaves the device.
                            if let badge = SyncPolicy.badge(
                                category: record.category, isSynced: record.isSynced, attempts: record.syncAttempts) {
                                Text(verbatim: "•")
                                    .font(.system(size: 10))
                                    .foregroundColor(.secondary.opacity(0.5))
                                    .accessibilityHidden(true)

                                SyncBadgeView(badge: badge)
                            }
                        }
                    }

                    Spacer(minLength: 0)
                }
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(PressableRowStyle())
            .accessibilityHint("Double tap to view details")

            Button {
                confirmingDelete = true
            } label: {
                Image(systemName: "trash")
                    .font(.system(size: 16))
                    .foregroundColor(MonTheme.error)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Delete")
            .confirmationDialog("Delete this item?", isPresented: $confirmingDelete, titleVisibility: .visible) {
                Button("Delete", role: .destructive) {
                    modelContext.delete(record)
                }
            }
        }
        .padding(.vertical, 6)
        .padding(.leading, 10)
        .padding(.trailing, 2)
        .background(MonTheme.surfaceColor.opacity(0.6))
        .cornerRadius(6)
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .stroke(Color.primary.opacity(0.06), lineWidth: 1)
        )
    }
}

/// The press feedback the row had through a long-press gesture, which fought
/// the tap; a button style gives it without a second gesture.
private struct PressableRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.98 : 1.0)
            .animation(.spring(response: 0.2, dampingFraction: 0.6), value: configuration.isPressed)
    }
}

private struct SyncBadgeView: View {
    let badge: SyncPolicy.Badge

    var body: some View {
        HStack(spacing: 2) {
            Image(systemName: icon)
                .font(.system(size: 10))
                .accessibilityHidden(true)
            text
                .font(.system(size: 10))
        }
        .foregroundColor(color)
    }

    private var icon: String {
        switch badge {
        case .synced: return "checkmark.icloud"
        case .pending: return "icloud.and.arrow.up"
        case .failed: return "exclamationmark.icloud"
        }
    }

    private var text: Text {
        switch badge {
        case .synced: return Text("Synced")
        case .pending: return Text("Pending")
        case .failed: return Text("Upload failed")
        }
    }

    private var color: Color {
        switch badge {
        case .synced: return .accentColor
        case .pending: return .secondary
        case .failed: return MonTheme.error
        }
    }
}

struct HistoryDetailView: View {
    let record: HistoryRecord
    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale
    @State private var copied = false

    /// Rendered from the saved metadata in the picked language. Rows saved
    /// before the metadata existed only have the English summary, so that is
    /// the fallback, never a reason to drop the warning.
    private var warning: String? {
        if let metadata = OcrReviewMetadata.decoded(from: record.ocrMetadata) {
            return metadata.localizedWarningSummary(in: AppLanguage(preference: locale.identifier))
        }
        return record.warningSummary
    }
    
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(record.fileName)
                            .font(MonTheme.Typography.section)
                            .fontWeight(.semibold)
                        Text(record.timestamp.formatted(date: .long, time: .shortened))
                            .font(MonTheme.Typography.meta)
                            .foregroundColor(.secondary)
                    }
                    
                    if let imgData = record.imageData {
                        if record.fileType == "application/pdf" {
                            // Render PDF preview on the fly (first page)
                            PDFPreview(data: imgData)
                        } else if let uiImage = UIImage(data: imgData) {
                            Image(uiImage: uiImage)
                                .resizable()
                                .scaledToFit()
                                .frame(maxWidth: .infinity, maxHeight: 140)
                                .cornerRadius(4)
                                .background(MonTheme.surfaceColor.opacity(0.3))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 4)
                                        .stroke(Color.white.opacity(0.1), lineWidth: 1)
                                )
                        }
                    }
                    
                    if let warning {
                        Label(warning, systemImage: "exclamationmark.triangle")
                            .font(MonTheme.Typography.meta)
                            .foregroundColor(MonTheme.warning)
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text("Text Content")
                            .font(MonTheme.Typography.section)
                        
                        Text(record.text)
                            .font(MonTheme.Typography.monBody(size: 16))
                            .padding()
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(MonTheme.surfaceColor.opacity(0.3))
                            .cornerRadius(4)
                    }
                    
                    Button {
                        UIPasteboard.general.string = record.text
                        withAnimation {
                            copied = true
                        }
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                            withAnimation {
                                copied = false
                            }
                        }
                    } label: {
                        Label(copied ? "Copied" : "Copy to Clipboard", systemImage: copied ? "checkmark.circle.fill" : "doc.on.doc")
                            .frame(maxWidth: .infinity)
                            .padding()
                            .background(copied ? Color.green.opacity(0.8) : Color.accentColor)
                            .foregroundColor(.black)
                            .fontWeight(.bold)
                            .cornerRadius(4)
                    }
                }
                .padding()
            }
            .navigationTitle("Scan Result")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: { dismiss() }) {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundColor(.secondary)
                    }
                    .accessibilityLabel("Close result")
                }
            }
        }
        .presentationDetents([.medium, .large])
        .preferredColorScheme(.dark)
    }
}
struct PDFPreview: View {
    let data: Data
    @State private var previewImage: UIImage? = nil
    
    var body: some View {
        Group {
            if let image = previewImage {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: .infinity, maxHeight: 140)
                    .cornerRadius(4)
                    .background(MonTheme.surfaceColor.opacity(0.3))
                    .overlay(
                        RoundedRectangle(cornerRadius: 4)
                            .stroke(Color.white.opacity(0.1), lineWidth: 1)
                    )
            } else {
                ZStack {
                    RoundedRectangle(cornerRadius: 4)
                        .fill(MonTheme.surfaceColor.opacity(0.3))
                    
                    VStack(spacing: 8) {
                        ProgressView()
                        Text("Rendering PDF...")
                            .font(.system(size: 10))
                            .foregroundColor(.secondary)
                    }
                }
                .frame(maxWidth: .infinity)
                .frame(height: 140)
            }
        }
        .task(id: data) {
            await render()
        }
    }
    
    private func render() async {
        // Run PDF rendering on background thread to keep UI smooth
        let result = await Task.detached(priority: .userInitiated) {
            let tempDir = FileManager.default.temporaryDirectory
            let tempFile = tempDir.appendingPathComponent("preview-\(UUID().uuidString).pdf")
            try? data.write(to: tempFile)
            
            let image = PdfUtil.renderPdfPageToImage(at: tempFile, pageIndex: 0)
            try? FileManager.default.removeItem(at: tempFile)
            return image
        }.value
        
        self.previewImage = result
    }
}
