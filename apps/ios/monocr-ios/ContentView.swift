import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

struct ContentView: View {
    @Environment(\.modelContext) private var modelContext
    @StateObject private var viewModel = MainViewModel()

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
                                SkeletonResultCard()
                                    .transition(.opacity.combined(with: .move(edge: .bottom)))
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
                            withAnimation { viewModel.clearResult() }
                        } label: {
                            Image(systemName: "arrow.clockwise")
                                .font(.system(size: 18, weight: .semibold)) // Smaller icon
                                .frame(width: 44, height: 44) // 44x44 standard but compact
                                .background(Color.accentColor)
                                .foregroundColor(.white)
                                .clipShape(RoundedRectangle(cornerRadius: 8))
                                .shadow(color: Color.accentColor.opacity(0.2), radius: 4, x: 0, y: 2) // Lighter shadow
                        }
                        .padding(24)
                        .transition(.scale.combined(with: .opacity))
                    }
                }
                .animation(.spring(response: 0.4, dampingFraction: 0.7), value: viewModel.ocrResult != nil)
                .animation(.spring(response: 0.4, dampingFraction: 0.7), value: viewModel.selectedImage != nil)
                
                Spacer()
                
                Spacer()
                
                ActionButtonsView(
                    selectedItem: $selectedItem,
                    showFileImporter: $showFileImporter,
                    showCamera: $viewModel.showCamera,
                    onImageSelected: { image in
                        viewModel.processImage(image, modelContext: modelContext)
                    },
                    onFileTooLarge: {
                        viewModel.errorMessage = NSLocalizedString("File too large (Max 50MB). Use CLI tools or desktop version for bigger file support.", comment: "")
                        viewModel.status = .error(NSLocalizedString("File size limit exceeded", comment: ""))
                    }
                )
                
                if let errorMessage = viewModel.errorMessage {
                    VStack(spacing: 12) {
                        Text(errorMessage)
                            .font(MonTheme.Typography.meta)
                            .foregroundColor(.red)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                        
                        if errorMessage.contains("CLI") {
                            Button {
                                showDocs = true
                            } label: {
                                Label("Learn more about CLI", systemImage: "terminal")
                                    .font(MonTheme.Typography.meta.bold())
                                    .foregroundColor(.accentColor)
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 8)
                                    .background(Color.accentColor.opacity(0.1))
                                    .clipShape(Capsule())
                            }
                        }
                    }
                    .padding(.top, 8)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
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
                            .font(.system(size: 18, weight: .semibold))
                    }
                }
                ToolbarItem(placement: .principal) {
                    if viewModel.selectedImage != nil {
                        Button("Clear") {
                            withAnimation {
                                viewModel.clearResult()
                            }
                        }
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        showAbout = true
                    } label: {
                        Image(systemName: "info.circle")
                    }
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
                    set: { if let img = $0 { viewModel.processImage(img, modelContext: modelContext) } }
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
                }
            }
            .overlay {
                if showCopyToast {
                    toastView
                }
            }
            .onChange(of: viewModel.status) { _, status in
                if case .ready = status, viewModel.ocrResult != nil {
                    let generator = UINotificationFeedbackGenerator()
                    generator.notificationOccurred(.success)
                }
            }
            .background(MonTheme.backgroundColor.ignoresSafeArea())
            .environment(\.locale, Locale(identifier: languagePref))
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
    }
    
    private func imagePreview(_ image: UIImage) -> some View {
        Image(uiImage: image)
            .resizable()
            .scaledToFit()
            .frame(maxHeight: 180) // Slightly smaller
            .cornerRadius(8)
            .shadow(color: Color.black.opacity(0.08), radius: 6, x: 0, y: 3)
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

