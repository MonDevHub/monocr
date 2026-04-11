import SwiftUI
import PhotosUI

import SwiftData

struct FeedbackView: View {
    @Environment(\.dismiss) var dismiss
    @Environment(\.modelContext) private var modelContext
    
    @StateObject private var viewModel: FeedbackViewModel
    @State private var selectedItem: PhotosPickerItem?
    @State private var showingDocumentPicker = false
    
    init(originalText: String = "", initialImage: UIImage? = nil) {
        _viewModel = StateObject(wrappedValue: FeedbackViewModel(originalText: originalText, initialImage: initialImage))
    }
    
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) { // Reduced from 24
                    // Original Source
                    VStack(alignment: .leading, spacing: 8) {
                        SectionHeader(title: "Original Source")
                        if let image = viewModel.sourceImage {
                            VStack(alignment: .leading, spacing: 8) {
                                Image(uiImage: image)
                                    .resizable()
                                    .scaledToFit()
                                    .frame(maxHeight: 120) // Reduced from 150
                                    .cornerRadius(4) // Reduced from 8
                                
                                Button("Change Source") {
                                    viewModel.sourceImage = nil
                                    viewModel.originalPdfData = nil
                                    selectedItem = nil
                                }
                                .font(.caption)
                            }
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
                                    subtitle: "Image or PDF",
                                    systemImage: "arrow.up.doc",
                                    action: { /* Menu handles it */ }
                                )
                            }
                        }
                    }
                    
                    // Original Output
                    if !viewModel.originalText.isEmpty {
                        VStack(alignment: .leading, spacing: 8) {
                            SectionHeader(title: "Original Output")
                            VStack(alignment: .leading, spacing: 12) {
                                Text("\"\(viewModel.originalText)\"")
                                    .font(MonTheme.Typography.monBody(size: 14))
                                    .foregroundColor(.secondary)
                                
                                HStack(spacing: 6) {
                                    Image(systemName: "info.circle")
                                        .font(.system(size: 10))
                                    Text("Report quality issues to help improve our model")
                                        .font(MonTheme.Typography.meta)
                                }
                                .foregroundColor(.secondary.opacity(0.8))
                            }
                            .padding(8) // Reduced from 16
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color(.secondarySystemBackground))
                            .cornerRadius(4) // Reduced from 8
                        }
                    }
                    
                    // Corrected Text
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            SectionHeader(title: "Corrected Text")
                            Spacer()
                            Text("VERIFIED BY HUMANS")
                                .font(.system(size: 9, weight: .bold))
                                .foregroundColor(.secondary.opacity(0.5))
                        }
                        
                        MonTextEditor(text: $viewModel.correctedText, placeholder: "Corrected Mon script...")
                    }
                    
                    // Error Type
                    VStack(alignment: .leading, spacing: 8) {
                        SectionHeader(title: "Error Type")
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 10) {
                                ForEach(viewModel.errorTypes, id: \.self) { type in
                                    Button {
                                        viewModel.selectedType = type
                                    } label: {
                                        Text(LocalizedStringKey(type))
                                            .font(MonTheme.Typography.meta)
                                            .fontWeight(.bold)
                                            .padding(.horizontal, 12)
                                            .padding(.vertical, 4)
                                            .background(viewModel.selectedType == type ? Color.accentColor : Color(.secondarySystemBackground))
                                            .foregroundColor(viewModel.selectedType == type ? .white : .primary)
                                            .cornerRadius(4)
                                    }
                                }
                            }
                        }
                    }
                    
                    // Consent & Action
                    VStack(spacing: 20) {
                        Divider()
                            .padding(.vertical, 8)
                        
                        HStack(alignment: .top, spacing: 12) {
                            Button {
                                viewModel.consent.toggle()
                            } label: {
                                Image(systemName: viewModel.consent ? "checkmark.square.fill" : "square")
                                    .foregroundColor(viewModel.consent ? .accentColor : .secondary)
                                    .font(.title3)
                            }
                            
                            VStack(alignment: .leading, spacing: 4) {
                                Text("I want to help improve MonOCR")
                                    .font(MonTheme.Typography.secondary)
                                    .fontWeight(.medium)
                                Text("Allow this feedback to be used for model verification and archival research.")
                                    .font(MonTheme.Typography.meta)
                                    .foregroundColor(.secondary)
                            }
                        }
                        
                        Button {
                            viewModel.submitFeedback(context: modelContext)
                            selectedItem = nil
                        } label: {
                            Text("Share Correction")
                                .fontWeight(.bold)
                                .frame(maxWidth: .infinity)
                                .frame(height: 36) // Reduced from 40
                                .background(viewModel.isSubmitDisabled ? Color.gray : Color.accentColor)
                                .foregroundColor(.white)
                                .cornerRadius(4)
                        }
                        .disabled(viewModel.isSubmitDisabled)
                        
                        Button("Cancel and discard") {
                            dismiss()
                        }
                        .font(MonTheme.Typography.secondary)
                        .foregroundColor(.secondary)
                        .padding(.vertical, 8)

                        // History Section for Feedback
                        HistorySection(title: "Accuracy Feedback", category: "feedback")
                    }
                }
                .padding(12) // Reduced from 24
            }
            .navigationTitle(LocalizedStringKey("Accuracy Feedback"))
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
}

