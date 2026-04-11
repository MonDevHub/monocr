import SwiftUI

struct PrivacyView: View {
    @Environment(\.dismiss) var dismiss
    
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    // Header
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Privacy Policy")
                            .font(MonTheme.Typography.title)
                            .fontWeight(.bold)
                        
                        Text("Effective Date: March 21, 2026")
                            .font(MonTheme.Typography.meta)
                            .foregroundColor(.secondary)
                    }
                    .padding(.bottom, 8)
                    
                    VStack(spacing: 8) {
                        PrivacySectionCard(
                            icon: "lock.fill",
                            title: "1. Zero Data Collection Promise",
                            content: "MonOCR is built from the ground up for absolute privacy. Our edge-AI architecture ensures that 100% of optical character recognition (OCR) processing occurs entirely on your device.\n\nWe do not use loud telemetry, we do not require an account, and we do not transmit your photos or documents to any external server."
                        )
                        
                        PrivacySectionCard(
                            icon: "icloud.slash.fill",
                            title: "2. Processing of Images",
                            content: "Any images you select from your library or capture via your camera are loaded straight into the Neural Processing Unit (ANE) on your local hardware. The original image and the generated text extraction both remain isolated in your device's memory."
                        )
                        
                        PrivacySectionCard(
                            icon: "checkmark.shield.fill",
                            title: "3. Voluntary Contributions",
                            content: "If you choose to use the \"Feedback\" or \"Contribute\" features, data is saved locally using SwiftData. It is not transmitted anywhere unless you manually choose to export and share those logs or screenshots with our engineering team."
                        )
                        
                        PrivacySectionCard(
                            icon: "doc.text.magnifyingglass",
                            title: "4. Diagnostic Data",
                            content: "To maintain privacy compliance, we do not use third-party cloud crash reporters. Diagnostic logs are written locally. You are in full control and can export them voluntarily if you encounter bugs."
                        )
                        
                        PrivacySectionCard(
                            icon: "arrow.triangle.2.circlepath",
                            title: "5. Changes to Policy",
                            content: "Because we do not collect data, we do not anticipate needing to update this policy often. Fundamental changes will be reflected in our open source repositories and in-app documentation."
                        )
                        
                        PrivacySectionCard(
                            icon: "envelope.fill",
                            title: "6. Contact Us",
                            content: "If you have any questions, please contact the MonDevHub engineering team via our public open source repositories on GitHub."
                        )
                    }
                }
                .padding(12)
            }
            .background(MonTheme.backgroundColor)
            .navigationBarItems(trailing: Button("Done") {
                dismiss()
            })
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

private struct PrivacySectionCard: View {
    let icon: String
    let title: LocalizedStringKey
    let content: LocalizedStringKey
    
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(MonTheme.Typography.section)
                    .foregroundColor(MonTheme.secondaryTextColor)
                    .frame(width: 20)
                
                Text(title)
                    .font(MonTheme.Typography.section)
                    .fontWeight(.bold)
            }
            
            Text(content)
                .font(MonTheme.Typography.body)
                .foregroundColor(.secondary)
                .lineSpacing(2)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(MonTheme.surfaceColor)
        .cornerRadius(8)
    }
}
