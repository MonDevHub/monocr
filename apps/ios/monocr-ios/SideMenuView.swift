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
                            Text("Version 1.0.0")
                                .font(MonTheme.Typography.meta)
                                .foregroundColor(.secondary)
                        }
                        .padding(.top, 60)
                        
                        // Language Selection
                        VStack(alignment: .leading, spacing: 12) {
                            Label("Language", systemImage: "translate")
                                .font(MonTheme.Typography.secondary.bold())
                                .foregroundColor(.primary)
                            
                            Picker("Language", selection: $languagePref) {
                                Text("English").tag("en")
                                Text("Burmese").tag("my")
                                Text("Mon").tag("mnw")
                            }
                            .pickerStyle(.segmented)
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
