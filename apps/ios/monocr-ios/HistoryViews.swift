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
                        HistoryItemView(record: record)
                            .onTapGesture {
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

struct HistoryItemView: View {
    let record: HistoryRecord
    @Environment(\.modelContext) private var modelContext
    @State private var isPressed = false
    
    var body: some View {
        HStack(spacing: 12) {
            // Icon
            ZStack {
                RoundedRectangle(cornerRadius: 6)
                    .fill(Color.accentColor.opacity(0.15))
                    .frame(width: 40, height: 40)
                
                Image(systemName: "doc.text")
                    .font(.system(size: 18))
                    .foregroundColor(.accentColor)
            }
            
            VStack(alignment: .leading, spacing: 4) {
                Text(record.fileName)
                    .font(MonTheme.Typography.meta)
                    .fontWeight(.bold)
                    .lineLimit(1)
                
                HStack(spacing: 6) {
                    Text(record.timestamp.formatted(.dateTime.month().day().hour().minute()))
                        .font(.system(size: 10))
                        .foregroundColor(.secondary)
                    
                    Text("•")
                        .font(.system(size: 10))
                        .foregroundColor(.secondary.opacity(0.5))
                    
                    HStack(spacing: 2) {
                        Image(systemName: record.isSynced ? "cloud.checkmark" : "cloud.arrow.up")
                            .font(.system(size: 10))
                        Text(record.isSynced ? "Synced" : "Pending")
                            .font(.system(size: 10))
                    }
                    .foregroundColor(record.isSynced ? .accentColor.opacity(0.8) : .secondary.opacity(0.7))
                }
            }
            
            Spacer()
            
            Button {
                modelContext.delete(record)
            } label: {
                Image(systemName: "trash")
                    .font(.system(size: 14))
                    .foregroundColor(.red.opacity(0.7))
            }
            .buttonStyle(.borderless)
        }
        .padding(10) // Reduced from 12
        .background(MonTheme.surfaceColor.opacity(0.6))
        .cornerRadius(6) // Reduced from 8
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .stroke(Color.white.opacity(0.05), lineWidth: 1)
        )
        .contentShape(Rectangle())
        .scaleEffect(isPressed ? 0.98 : 1.0)
        .animation(.spring(response: 0.2, dampingFraction: 0.6), value: isPressed)
        .onLongPressGesture(minimumDuration: .infinity, pressing: { pressing in
            withAnimation { self.isPressed = pressing }
        }, perform: {})
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Record: \(record.fileName), dated \(record.timestamp.formatted())")
        .accessibilityHint("Double tap to view details")
    }
}

struct HistoryDetailView: View {
    let record: HistoryRecord
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false
    
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
