import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.modelContext) private var modelContext
    @StateObject private var viewModel = MainViewModel()

    @Environment(\.dynamicTypeSize) private var typeSize
    @AppStorage("language_pref") private var languagePref: String = "en"
    
    @State private var selectedItem: PhotosPickerItem?
    @State private var showCopyToast = false
    @State private var showSaveToast = false
    @State private var showAbout = false
    @State private var showDocs = false
    @State private var showContribute = false
    @State private var showFeedback = false
    @State private var showPrivacy = false
    @State private var showFileImporter = false
    @State private var showFullImage = false
    @State private var showMenu = false
    
    var body: some View {
        ZStack {
            NavigationStack {
            VStack(spacing: 0) {
                // Engine Status Bar
                EngineStatusHeaderView(status: viewModel.status)
                
                ZStack(alignment: .bottomTrailing) {
                    ScrollView {
                        VStack(spacing: 12) { // Tighter spacing
                            if typeSize.isAccessibilitySize {
                                controls
                            }

                            if let image = viewModel.selectedImage {
                                Button {
                                    showFullImage = true
                                } label: {
                                    imagePreview(image)
                                }
                                .buttonStyle(PlainButtonStyle())
                            } else {
                                ContentUnavailableView(
                                    "MonOCR",
                                    systemImage: "text.viewfinder",
                                    description: Text("Select an image or use the camera to extract Mon text.")
                                )
                                .padding(.top, 32)
                            }

                            if viewModel.isProcessing {
                                processingOverlay
                                progressAndCancel
                                // A PDF publishes its reading after every page, so
                                // show it as it grows instead of a shimmer that hid
                                // every page already read until the last one.
                                if let partial = viewModel.ocrResult {
                                    ResultCardView(
                                        result: partial,
                                        showCopyToast: $showCopyToast,
                                        onReport: { showFeedback = true }
                                    )
                                } else {
                                    SkeletonResultCard()
                                        .transition(.opacity.combined(with: .move(edge: .bottom)))
                                }
                            } else if let result = viewModel.ocrResult {
                                ResultCardView(
                                    result: result,
                                    showCopyToast: $showCopyToast,
                                    onReport: { showFeedback = true }
                                )
                            } else if viewModel.selectedImage == nil {
                                // Only show history when there is no active image processing
                                VStack(spacing: 32) {
                                    HistorySection(title: "Recent Scans", category: "scan")
                                }
                                .padding(.top, 24)
                            }
                        }
                        .padding()
                    }

                    // FAB: New Scan — shown when a result is visible, matches Android FAB
                    if viewModel.ocrResult != nil || viewModel.selectedImage != nil {
                        Button {
                            withAnimation { viewModel.clearResult(modelContext: modelContext); selectedItem = nil }
                        } label: {
                            // `plus`, not `arrow.clockwise`: this clears the result for
                            // a new scan, it does not re-read the current one.
                            Image(systemName: "plus")
                                .font(.body.weight(.semibold))
                                .frame(width: 44, height: 44)
                                .background(Color.accentColor)
                                .foregroundColor(.white)
                                .clipShape(RoundedRectangle(cornerRadius: 8))
                                .shadow(color: Color.accentColor.opacity(0.2), radius: 4, x: 0, y: 2) // Lighter shadow
                        }
                        .accessibilityLabel("New scan")
                        .padding(24)
                        .transition(.scale.combined(with: .opacity))
                    }
                }
                .animation(.spring(response: 0.4, dampingFraction: 0.7), value: viewModel.ocrResult != nil)
                .animation(.spring(response: 0.4, dampingFraction: 0.7), value: viewModel.selectedImage != nil)


                // Pinned under the results normally. At accessibility sizes they
                // move into the scroll view: pinned, they took most of the screen
                // and left about a fifth of it for the content.
                if !typeSize.isAccessibilitySize {
                    controls
                }
            }
            .navigationTitle("Mon OCR")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button {
                        withAnimation(.spring()) {
                            showMenu = true
                        }
                    } label: {
                        Image(systemName: "line.3.horizontal")
                            .font(.body.weight(.semibold))
                            .frame(minWidth: 44, minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel("Menu")
                }
                ToolbarItem(placement: .principal) {
                    if viewModel.selectedImage != nil {
                        Button("Clear") {
                            withAnimation {
                                viewModel.clearResult(modelContext: modelContext); selectedItem = nil
                            }
                        }
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        showAbout = true
                    } label: {
                        Image(systemName: "info.circle")
                            .frame(minWidth: 44, minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel("About")
                }
            }
            .sheet(isPresented: $showAbout) {
                AboutView(
                    showDocs: $showDocs,
                    showContribute: $showContribute,
                    showFeedback: $showFeedback
                )
            }
            .sheet(isPresented: $showDocs) {
                DocsView()
            }
            .sheet(isPresented: $showContribute) {
                ContributeView()
            }
            .sheet(isPresented: $showFeedback) {
                FeedbackView(
                    originalText: viewModel.ocrResult?.text ?? "",
                    initialImage: viewModel.selectedImage
                )
            }
            .sheet(isPresented: $showPrivacy) {
                PrivacyView()
            }
            .fullScreenCover(isPresented: $showFullImage) {
                if let image = viewModel.selectedImage {
                    FullScreenImageView(image: image, isPresented: $showFullImage)
                }
            }
            .sheet(isPresented: $viewModel.showCamera) {
                CameraPicker(image: Binding(
                    get: { viewModel.selectedImage },
                    set: {
                        if let img = $0 {
                            viewModel.processImage(img, provenance: .cameraCapture, modelContext: modelContext)
                        }
                    }
                ))
            }
            .fileImporter(
                isPresented: $showFileImporter,
                allowedContentTypes: [.pdf],
                allowsMultipleSelection: false
            ) { result in
                switch result {
                case .success(let urls):
                    if let url = urls.first {
                        viewModel.processPdf(at: url, modelContext: modelContext)
                    }
                case .failure(let error):
                    MonLogger.e("File picker failed: \(error.localizedDescription)")
                    viewModel.errorMessage = .fileOpenFailed(error.localizedDescription)
                }
            }
            .overlay {
                if showCopyToast {
                    toastView
                }
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .background {
                    selectedItem = nil
                    viewModel.cancelProcessing(modelContext: modelContext)
                }
            }
            .onChange(of: viewModel.status) { _, status in
                // Not for a cancelled reading, which also returns to ready.
                if case .ready = status, let result = viewModel.ocrResult, !result.cancelled {
                    let generator = UINotificationFeedbackGenerator()
                    generator.notificationOccurred(.success)
                }
            }
            .background(MonTheme.backgroundColor.ignoresSafeArea())
            }
            
            SideMenuView(
                isShowing: $showMenu,
                showAbout: $showAbout,
                showDocs: $showDocs,
                showContribute: $showContribute,
                showFeedback: $showFeedback,
                showPrivacy: $showPrivacy
            )
        }
        // On the ZStack, not the NavigationStack, so the side menu follows the
        // picker too: it sat outside this modifier and never changed language.
        .environment(\.locale, AppLanguage(preference: languagePref).locale)
    }
    
    /// The segmentation picker, the three source buttons and any error.
    private var controls: some View {
        VStack(spacing: 0) {
            SegmentationModePicker(
                mode: viewModel.segmentationMode,
                isEnabled: !viewModel.isProcessing,
                onSelect: { mode in
                    viewModel.selectSegmentationMode(mode, modelContext: modelContext)
                }
            )

            ActionButtonsView(
                selectedItem: $selectedItem,
                showFileImporter: $showFileImporter,
                showCamera: $viewModel.showCamera,
                onImageSelected: { image in
                    viewModel.processImage(image, provenance: .photoLibrary, modelContext: modelContext)
                },
                onImageUnreadable: {
                    viewModel.errorMessage = .imageUnreadable
                },
                onFileTooLarge: {
                    viewModel.errorMessage = .fileTooLarge
                    viewModel.status = .error(AppLocalization.string("File size limit exceeded"))
                }
            )

            if let errorMessage = viewModel.errorMessage {
                VStack(spacing: 12) {
                    Text(verbatim: errorMessage.text(in: AppLanguage(preference: languagePref)))
                        .font(MonTheme.Typography.meta)
                        .foregroundColor(.red)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)

                    if errorMessage.offersCliHelp {
                        Button {
                            showDocs = true
                        } label: {
                            Label("Learn more about CLI", systemImage: "terminal")
                                .font(MonTheme.Typography.meta.bold())
                                .foregroundColor(.accentColor)
                                .padding(.horizontal, 16)
                                .padding(.vertical, 8)
                                .frame(minHeight: 44)
                                .background(Color.accentColor.opacity(0.1))
                                .clipShape(Capsule())
                        }
                    }
                }
                .padding(.top, 8)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
    }

    private func imagePreview(_ image: UIImage) -> some View {
        Image(uiImage: image)
            .resizable()
            .scaledToFit()
            .frame(maxHeight: 180) // Slightly smaller
            .cornerRadius(8)
            .shadow(color: Color.black.opacity(0.08), radius: 6, x: 0, y: 3)
    }
    
    /// How far the read has got, and a labelled way out. The only way to stop a
    /// long PDF was the unlabelled new-scan button, and nothing said how long
    /// was left. Cancelling keeps the pages read so far.
    private var progressAndCancel: some View {
        VStack(spacing: 8) {
            if case .processing(let progress?) = viewModel.status {
                ProgressView(value: progress.fraction) {
                    Text("Reading: \(progress.done) of \(progress.total) pages done")
                        .font(MonTheme.Typography.secondary)
                }
            }

            Button(role: .cancel) {
                withAnimation { viewModel.cancelProcessing(modelContext: modelContext) }
            } label: {
                Label("Cancel", systemImage: "xmark.circle")
                    .font(MonTheme.Typography.secondary.weight(.semibold))
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.bordered)
        }
    }

    private var processingOverlay: some View {
        ZStack {
            if let image = viewModel.selectedImage {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxHeight: 200)
                    .cornerRadius(16)
                    .overlay {
                        Color.black.opacity(0.4)
                            .cornerRadius(16)
                    }
                
                VStack(spacing: 12) {
                    ProgressView()
                        .tint(.white)
                        .controlSize(.large)
                    Text("main_scanning")
                        .font(MonTheme.Typography.secondary)
                        .fontWeight(.medium)
                        .foregroundColor(.white)
                }
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }
    
    private var toastView: some View {
        VStack {
            Spacer()
            Text("Copied to clipboard")
                .font(MonTheme.Typography.meta)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(Color.black.opacity(0.8))
                .foregroundColor(.white)
                .cornerRadius(20)
                .padding(.bottom, 32)
        }
        .frame(maxWidth: .infinity)
    }
}

#Preview {
    ContentView()
}
