import SwiftUI
import PhotosUI

struct ActionButtonsView: View {
    @Binding var selectedItem: PhotosPickerItem?
    @Binding var showFileImporter: Bool
    @Binding var showCamera: Bool
    let onImageSelected: (UIImage) -> Void
    let onFileTooLarge: () -> Void
    
    var body: some View {
        HStack(spacing: 15) {
            PhotosPicker(selection: $selectedItem, matching: .images) {
                VStack {
                    Image(systemName: "photo.on.rectangle")
                        .font(.title3)
                    Text("Gallery")
                        .font(.caption)
                        .fontWeight(.medium)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(Color.accentColor.opacity(0.1))
                .cornerRadius(12)
            }
            .onChange(of: selectedItem) { _, newItem in
                let generator = UIImpactFeedbackGenerator(style: .medium)
                generator.impactOccurred()
                Task {
                    if let data = try? await newItem?.loadTransferable(type: Data.self) {
                        if data.count > 50 * 1024 * 1024 {
                            onFileTooLarge()
                            return
                        }
                        if let image = UIImage(data: data) {
                            onImageSelected(image)
                        }
                    }
                }
            }
            
            Button {
                let generator = UIImpactFeedbackGenerator(style: .medium)
                generator.impactOccurred()
                showFileImporter = true
            } label: {
                VStack {
                    Image(systemName: "doc.text.fill")
                        .font(.title3)
                    Text("PDF")
                        .font(.caption)
                        .fontWeight(.medium)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(Color.accentColor.opacity(0.1))
                .cornerRadius(12)
            }
            
            Button {
                let generator = UIImpactFeedbackGenerator(style: .medium)
                generator.impactOccurred()
                showCamera = true
            } label: {
                VStack {
                    Image(systemName: "camera")
                        .font(.title3)
                    Text("Camera")
                        .font(.caption)
                        .fontWeight(.medium)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(Color.accentColor)
                .foregroundColor(.white)
                .cornerRadius(12)
            }
        }
        .padding()
    }
}
