import SwiftUI

struct SectionHeader: View {
    let title: String
    
    var body: some View {
        Text(title.uppercased())
            .font(MonTheme.Typography.meta) // Raised from caption2 to constitutional meta (12pt)
            .fontWeight(.bold)
            .foregroundColor(.secondary)
            .kerning(1.0)
            .padding(.bottom, 4)
    }
}

struct DashedBox: View {
    let title: String
    let subtitle: String
    let systemImage: String
    let action: () -> Void
    
    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: systemImage)
                    .font(.title3)
                    .foregroundColor(.accentColor)
                    .padding(8)
                    .background(Color.accentColor.opacity(0.1))
                    .cornerRadius(4) // Reduced from 12
                
                VStack(spacing: 2) {
                    Text(title)
                        .font(MonTheme.Typography.body)
                        .fontWeight(.semibold)
                        .foregroundColor(.primary)
                    
                    Text(subtitle.uppercased())
                        .font(MonTheme.Typography.meta.bold()) // Raised from 10pt to 12pt
                        .foregroundColor(.secondary)
                        .kerning(0.5)
                }
            }
            .padding(12) // Reduced from 24
            .frame(maxWidth: .infinity)
            .frame(minHeight: 100)
            .background(Color.accentColor.opacity(0.01))
            .cornerRadius(8) // Reduced from 16
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(style: StrokeStyle(lineWidth: 0.5, dash: [4])) // Hairline stroke
                    .foregroundColor(Color.accentColor.opacity(0.2))
            )
        }
        .buttonStyle(PlainButtonStyle())
    }
}

struct MonTextEditor: View {
    @Binding var text: String
    let placeholder: String
    
    var body: some View {
        ZStack(alignment: .topLeading) {
            if text.isEmpty {
                Text(placeholder)
                    .font(MonTheme.Typography.monBody(size: 16))
                    .foregroundColor(Color(.placeholderText))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 12)
            }
            
            TextEditor(text: $text)
                .font(MonTheme.Typography.monBody(size: 16))
                .lineSpacing(4)
                .scrollContentBackground(.hidden)
                .background(Color(.systemBackground))
        }
        .frame(minHeight: 100) // Reduced from 120
        .padding(6)
        .background(Color(.secondarySystemBackground))
        .cornerRadius(8)
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(Color(.separator), lineWidth: 0.5)
        )
    }
}

/// Consent to upload, shared by Feedback and Contribute so both ask the same
/// question in the same words.
///
/// A real `Toggle`, so VoiceOver reads its caption and state as one control.
/// Feedback used an image-only button with the caption beside it, which
/// VoiceOver announced as an unnamed button.
struct UploadConsentToggle: View {
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 4) {
                Text("I want to help improve MonOCR")
                    .font(MonTheme.Typography.secondary)
                    .fontWeight(.medium)
                Text("Submitting uploads the text and any attached image or PDF to the MonDevHub feedback service, where they are used to check and improve the model.")
                    .font(MonTheme.Typography.meta)
                    .foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .tint(.accentColor)
    }
}

typealias Unit = () -> Void
