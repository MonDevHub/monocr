import SwiftUI
import PhotosUI

import SwiftData

struct ContributeView: View {
    @Environment(\.dismiss) var dismiss
    @Environment(\.modelContext) private var modelContext
    
    @StateObject private var viewModel = ContributeViewModel()
    @State private var selectedItem: PhotosPickerItem?
    
    @State private var showingDocumentPicker = false
    
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .center, spacing: 16) { // Reduced from 32
                    heroHeader
                    
                    uploadSection
                    
                    orDivider
                    
                    transcriptionSection
                    
                    submitAction
                    
                    Spacer.height(20) // Reduced from 40
                }
                .padding(12) // Reduced from 20
            }
            .navigationTitle("Contribute")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .onChange(of: selectedItem) { _, newItem in
                Task {
                    if let data = try? await newItem?.loadTransferable(type: Data.self),
                       let image = UIImage(data: data) {
                        viewModel.sourceImage = image
                        viewModel.originalPdfData = nil
                    }
                }
            }
            .sheet(isPresented: $showingDocumentPicker) {
                DocumentPicker(types: [.pdf]) { url in
                    viewModel.handleSelectedPDF(at: url)
                } onCancel: {
                    showingDocumentPicker = false
                }
            }
        }
    }
    
    private var heroHeader: some View {
        VStack(spacing: 4) { // Reduced from 8
            Image(systemName: "hand.raised.fill")
                .font(.system(size: 32)) // Reduced from 40
                .foregroundColor(.accentColor)
                .padding(.bottom, 4)
            
            Text("Preserve Our Heritage")
                .font(.title3.bold()) // Slightly smaller
                .textAlign(.center)
            
            Text("Contribute Mon documents to improve accuracy and archival quality.")
                .font(MonTheme.Typography.meta) // Reduced from subheadline
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 16)
        }
        .padding(.top, 12) // Reduced from 20
    }
    
    private var uploadSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "Submit Mon Documents")
            
            if let image = viewModel.sourceImage {
                 VStack(alignment: .leading, spacing: 10) {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .frame(maxHeight: 180) // Reduced from 200
                        .cornerRadius(8) // Reduced from 16
                        .shadow(radius: 2)
                    
                    Button("Remove selected source") {
                        viewModel.sourceImage = nil
                        viewModel.originalPdfData = nil
                        selectedItem = nil
                    }
                    .font(.caption)
                    .foregroundColor(.red)
                }
                .frame(maxWidth: .infinity)
            } else {
                Menu {
                    PhotosPicker(selection: $selectedItem, matching: .images) {
                        Label("Photo Library", systemImage: "photo.on.rectangle")
                    }
                    Button {
                        showingDocumentPicker = true
                    } label: {
                        Label("Files (PDF)", systemImage: "doc.badge.plus")
                    }
                } label: {
                    DashedBox(
                        title: "Upload Original Scan",
                        subtitle: "PDF or Images",
                        systemImage: "doc.badge.plus",
                        action: { /* No-op, menu handles it */ }
                    )
                }
            }
        }
    }
    
    private var orDivider: some View {
        HStack {
            VStack { Divider() }
            Text("OR")
                .font(.system(size: 10, weight: .bold))
                .foregroundColor(.secondary.opacity(0.5))
                .padding(.horizontal, 10)
            VStack { Divider() }
        }
    }
    
    private var transcriptionSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: "Type Mon Script")
            MonTextEditor(
                text: $viewModel.transcription,
                placeholder: "Example: မန်ဒိုင် (Type or paste the Mon script here)..."
            )
        }
    }
    
    private var submitAction: some View {
        VStack(spacing: 24) {
            Button {
                viewModel.submitContribution(context: modelContext)
                selectedItem = nil
            } label: {
                Text("Submit Contribution")
                    .fontWeight(.bold)
                    .frame(maxWidth: .infinity)
                    .frame(height: 40) // Reduced from 52
                    .background(viewModel.isSubmitDisabled ? Color.gray : Color.accentColor)
                    .foregroundColor(.white)
                    .cornerRadius(4) // Reduced from 12
            }
            .disabled(viewModel.isSubmitDisabled)

            // History Section for Contributions
            HistorySection(title: "Contributions", category: "contribution")
        }
    }


}

extension View {
    func textAlign(_ alignment: TextAlignment) -> some View {
        self.multilineTextAlignment(alignment)
    }
}

extension Spacer {
    static func height(_ height: CGFloat) -> some View {
        Spacer().frame(height: height)
    }
}
