import SwiftUI

struct SideMenuView: View {
    @Binding var isShowing: Bool
    @AppStorage("language_pref") private var languagePref: String = "en"
    
    // Bindings for parent navigation
    @Binding var showAbout: Bool
    @Binding var showDocs: Bool
    @Binding var showContribute: Bool
    @Binding var showFeedback: Bool
    @Binding var showPrivacy: Bool

    /// The shipped version, read from the bundle rather than typed in.
    /// AboutView derives the same string the same way; this screen carried a
    /// literal "Version 1.0.0" against a MARKETING_VERSION of 1.0.3.
    private static var versionString: String {
        let short = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"
        return "Version \(short)"
    }

    var body: some View {
        ZStack {
            if isShowing {
                // Dimmed background
                Color.black.opacity(0.25)
                    .ignoresSafeArea()
                    .onTapGesture {
                        withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) {
                            isShowing = false
                        }
                    }
                
                HStack {
                    VStack(alignment: .leading, spacing: 32) {
                        // Header
                        VStack(alignment: .leading, spacing: 4) {
                            Text("MonOCR")
                                .font(MonTheme.Typography.title.bold())
                                .foregroundColor(.primary)
                            // Read from the bundle, never typed in. This said
                            // "Version 1.0.0" while MARKETING_VERSION was 1.0.3,
                            // so the two screens of this app disagreed —
                            // AboutView has always read the bundle.
                            Text(Self.versionString)
                                .font(MonTheme.Typography.meta)
                                .foregroundColor(.secondary)
                        }
                        .padding(.top, 60)
                        
                        // Language Selection
                        VStack(alignment: .leading, spacing: 12) {
                            Label("Language", systemImage: "translate")
                                .font(MonTheme.Typography.secondary.bold())
                                .foregroundColor(.primary)
                            
                            // Each language named in its own script, verbatim, so a
                            // reader who cannot read the current interface language
                            // can still find theirs. A segmented control truncated
                            // these names, so it is a list of rows.
                            VStack(spacing: 4) {
                                ForEach(AppLanguage.allCases) { language in
                                    LanguageRow(
                                        name: language.endonym,
                                        isSelected: AppLanguage(preference: languagePref) == language
                                    ) {
                                        languagePref = language.rawValue
                                    }
                                }
                            }
                        }
                        
                        Divider()
                            .opacity(0.5)
                        
                        // Navigation Links
                        VStack(alignment: .leading, spacing: 8) {
                            MenuButton(title: "Docs", icon: "book", action: { showDocs = true; isShowing = false })
                            MenuButton(title: "Contribute", icon: "hand.raised", action: { showContribute = true; isShowing = false })
                            MenuButton(title: "Feedback", icon: "text.bubble", action: { showFeedback = true; isShowing = false })
                            MenuButton(title: "Privacy", icon: "shield.lefthalf.filled", action: { showPrivacy = true; isShowing = false })
                            MenuButton(title: "About", icon: "info.circle", action: { showAbout = true; isShowing = false })
                        }
                        
                        Spacer()
                    }
                    .padding(24)
                    .frame(width: 280)
                    .background(
                        ZStack {
                            MonTheme.surfaceColor
                            Rectangle()
                                .fill(.ultraThinMaterial) // Premium Glassmorphism
                        }
                    )
                    .ignoresSafeArea()
                    .transition(.move(edge: .leading))
                    
                    Spacer()
                }
            }
        }
    }
}

struct MenuButton: View {
    let title: LocalizedStringKey
    let icon: String
    let action: () -> Void
    
    var body: some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Image(systemName: icon)
                    .font(.system(size: 18))
                    .frame(width: 24)
                    .foregroundColor(.accentColor)
                Text(title)
                    .font(MonTheme.Typography.body.weight(.medium))
                    .foregroundColor(.primary)
            }
            .padding(.vertical, 12)
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.accentColor.opacity(0.05))
            .cornerRadius(12)
        }
        .buttonStyle(PlainButtonStyle())
    }
}

private struct LanguageRow: View {
    let name: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                Text(verbatim: name)
                    .font(MonTheme.Typography.body)
                    .foregroundColor(.primary)
                Spacer()
                if isSelected {
                    Image(systemName: "checkmark")
                        .foregroundColor(.accentColor)
                        .accessibilityHidden(true)
                }
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .background(isSelected ? Color.accentColor.opacity(0.1) : Color.clear)
            .cornerRadius(8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
    }
}
