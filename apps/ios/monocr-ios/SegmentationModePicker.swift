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

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Picker(
                "Segmentation",
                selection: Binding(get: { mode }, set: { onSelect($0) })
            ) {
                ForEach(SegmentationMode.allCases) { option in
                    Text(option.label).tag(option)
                }
            }
            .pickerStyle(.segmented)
            .disabled(!isEnabled)

            Text(mode.detail)
                .font(MonTheme.Typography.meta)
                .foregroundColor(.secondary)
        }
        .padding(.horizontal)
    }
}
