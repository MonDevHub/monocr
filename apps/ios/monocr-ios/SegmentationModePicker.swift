import SwiftUI

/**
 Lets the user pick how the page is cut into lines.

 The default comes from where the image came from (see `ImageProvenance`), which
 is right often enough to be worth preselecting and wrong often enough to be
 worth overriding: a photo of a book page wants `page`, a slide wants `sparse`.
 There is deliberately no automatic switch on confidence — upstream measured 0.83
 confidence on a reading that appears nowhere on the page.

 The selection is written through a callback rather than bound straight to the
 view model, so setting the provenance default cannot look like a user choice and
 re-trigger a scan.
 */
struct SegmentationModePicker: View {
    let mode: SegmentationMode
    let isEnabled: Bool
    let onSelect: (SegmentationMode) -> Void
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            // A segmented control does not grow with Dynamic Type, so at
            // accessibility sizes the same choice is a menu, which does.
            if typeSize.isAccessibilitySize {
                picker.pickerStyle(.menu)
            } else {
                picker.pickerStyle(.segmented)
            }

            Text(LocalizedStringKey(mode.detailKey))
                .font(MonTheme.Typography.meta)
                .foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal)
    }

    private var picker: some View {
        Picker(
            "Segmentation",
            selection: Binding(get: { mode }, set: { onSelect($0) })
        ) {
            ForEach(SegmentationMode.allCases) { option in
                Text(LocalizedStringKey(option.labelKey)).tag(option)
            }
        }
        .disabled(!isEnabled)
    }
}
