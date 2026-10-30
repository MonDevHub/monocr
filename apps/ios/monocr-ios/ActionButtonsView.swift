import SwiftUI
import PhotosUI

struct ActionButtonsView: View {
    @Binding var selectedItem: PhotosPickerItem?
    @Binding var showFileImporter: Bool
    @Binding var showCamera: Bool
    let onImageSelected: (UIImage) -> Void
    /// The library returned nothing, or bytes that are not an image. This
    /// used to end silently, so the button looked broken.
    let onImageUnreadable: () -> Void
    let onFileTooLarge: () -> Void
    
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var cameraProblem: CameraAccess.Decision?

    /// Side by side normally; stacked at accessibility sizes, where three
    /// columns broke the captions mid-word ("Galler/y", "Cam-era").
    private var layout: AnyLayout {
        typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(spacing: 8)) : AnyLayout(HStackLayout(spacing: 15))
    }

    var body: some View {
        layout {
            PhotosPicker(selection: $selectedItem, matching: .images) {
                ActionLabel(title: "Gallery", systemImage: "photo.on.rectangle", prominent: false)
            }
            .task(id: selectedItem) {
                guard let newItem = selectedItem else { return }
                let generator = UIImpactFeedbackGenerator(style: .medium)
                generator.impactOccurred()
                let data = try? await newItem.loadTransferable(type: Data.self)
                guard !Task.isCancelled else { return }
                guard let data else {
                    onImageUnreadable()
                    return
                }
                if SubmissionRules.importTooLarge(byteCount: Int64(data.count)) {
                    onFileTooLarge()
                    return
                }
                if let image = UIImage(data: data) {
                    onImageSelected(image)
                } else {
                    onImageUnreadable()
                }
            }
            
            Button {
                let generator = UIImpactFeedbackGenerator(style: .medium)
                generator.impactOccurred()
                showFileImporter = true
                selectedItem = nil
            } label: {
                ActionLabel(title: "PDF", systemImage: "doc.text.fill", prominent: false)
            }
            
            Button {
                let generator = UIImpactFeedbackGenerator(style: .medium)
                generator.impactOccurred()
                selectedItem = nil
                openCamera()
            } label: {
                ActionLabel(title: "Camera", systemImage: "camera", prominent: true)
            }
        }
        .padding()
        .modifier(CameraAccessAlert(decision: $cameraProblem))
    }

    /// Checks before presenting. The picker used to open unconditionally: an
    /// exception or a blank sheet with no camera, a black picker with access off.
    private func openCamera() {
        switch CameraPermission.decide() {
        case .present:
            showCamera = true
        case .requestThenPresent:
            Task {
                if await CameraPermission.request() {
                    showCamera = true
                } else {
                    cameraProblem = .explainDenied
                }
            }
        case let problem:
            cameraProblem = problem
        }
    }
}

/// One of the three source buttons. Icon over caption normally, icon beside
/// caption at accessibility sizes, where the buttons stack.
private struct ActionLabel: View {
    let title: LocalizedStringKey
    let systemImage: String
    let prominent: Bool
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        Group {
            if typeSize.isAccessibilitySize {
                Label(title, systemImage: systemImage)
                    .font(.body.weight(.medium))
            } else {
                VStack {
                    Image(systemName: systemImage)
                        .font(.title3)
                    Text(title)
                        .font(.caption)
                        .fontWeight(.medium)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
            }
        }
        .frame(maxWidth: .infinity, minHeight: 44)
        .padding(.vertical, 12)
        .background(prominent ? Color.accentColor : Color.accentColor.opacity(0.1))
        .foregroundColor(prominent ? .white : .accentColor)
        .cornerRadius(12)
    }
}
