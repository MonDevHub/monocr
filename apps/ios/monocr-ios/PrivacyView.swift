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
                        
                        Text("Effective Date: October 29, 2026")
                            .font(MonTheme.Typography.meta)
                            .foregroundColor(.secondary)
                    }
                    .padding(.bottom, 8)
                    
                    // What the code does, checked against SyncService: scans are
                    // never uploaded, Feedback and Contribute are, on submit. This
                    // screen said nothing was transmitted, and that images went to
                    // the Neural Engine, which has never been checked on a device.
                    VStack(spacing: 8) {
                        PrivacySectionCard(
                            icon: "lock.fill",
                            title: "1. Scans stay on your device",
                            content: "MonOCR reads text on your device with Core ML. The images, PDFs and camera photos you scan, and the text read from them, are not sent anywhere unless you include them in Feedback or a contribution (section 3). There is no account and no analytics."
                        )

                        PrivacySectionCard(
                            icon: "clock.arrow.circlepath",
                            title: "2. Scan history",
                            content: "Scans are saved in the app's history on this device so you can open them again. You can delete them from the history at any time, and deleting the app deletes them."
                        )

                        PrivacySectionCard(
                            icon: "icloud.and.arrow.up",
                            title: "3. Feedback and contributions are uploaded",
                            content: "Feedback and Contribute are the only screens that send anything. When you submit one, its text and any image or PDF attached to it are uploaded to the MonDevHub feedback service, where they are used to check and improve the recognition model. Feedback starts with the scan you are reporting attached; you can change or remove it. Both screens ask for your consent first, and nothing is sent until you submit. Each submission is also kept in that screen's history, which shows whether it has been uploaded."
                        )

                        PrivacySectionCard(
                            icon: "doc.text.magnifyingglass",
                            title: "4. Diagnostic Data",
                            content: "To maintain privacy compliance, we do not use third-party cloud crash reporters. Diagnostic logs are written locally. You are in full control and can export them voluntarily if you encounter bugs."
                        )

                        PrivacySectionCard(
                            icon: "arrow.triangle.2.circlepath",
                            title: "5. Changes to Policy",
                            content: "Changes to this policy will be published in the project's open-source repository and on this screen."
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
