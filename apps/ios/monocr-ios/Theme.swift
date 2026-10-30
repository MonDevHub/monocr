import SwiftUI

struct MonTheme {
    static let backgroundColor = Color(light: Color(red: 0.984, green: 0.984, blue: 0.984), // #FBFBFB
                                     dark: Color(red: 0.082, green: 0.082, blue: 0.090))   // #151517
    
    static let surfaceColor = Color(light: Color(red: 0.98, green: 0.98, blue: 0.98),    // #FAFAFA
                                   dark: Color(red: 0.094, green: 0.094, blue: 0.106))   // #18181B

    static let secondaryTextColor = Color.secondary.opacity(0.8)
    
    // Semantic status colours. Warning and error are text at caption sizes, so
    // their light variants are darkened to at least 4.5:1 on the light
    // backgrounds (#A65300 is 4.9:1 on #F2F2F7, #C9251B is 5.0:1); system orange
    // was 2.0:1 and system red 3.2:1. The dark variants are the system colours,
    // above 5:1 on #151517. Success only colours the status dot.
    static let success = Color.green
    static let warning = Color(light: Color(red: 0xA6 / 255, green: 0x53 / 255, blue: 0x00 / 255),
                               dark: Color(red: 0xFF / 255, green: 0x9F / 255, blue: 0x0A / 255))
    static let error = Color(light: Color(red: 0xC9 / 255, green: 0x25 / 255, blue: 0x1B / 255),
                             dark: Color(red: 0xFF / 255, green: 0x45 / 255, blue: 0x3A / 255))
    
    /// Text styles, so every size follows Dynamic Type. These were fixed
    /// `Font.system(size:)` values (20, 14, 13, 12, 11) that never scaled; the
    /// styles below are the same sizes at the default setting, except `section`,
    /// which is 15 rather than 14 because no text style is 14.
    struct Typography {
        static let title     = Font.title3.weight(.semibold)       // 20
        static let section   = Font.subheadline.weight(.semibold)  // 15
        static let body      = Font.footnote                       // 13
        static let secondary = Font.caption.weight(.medium)        // 12
        static let meta      = Font.caption2                       // 11
        
        /// Mon text. The name must be the font's own, and `Font.custom` fails
        /// **silently** to the system font when it is not.
        ///
        /// This read `"PyidaungSu-Regular"` until 2026-08-26. No name record in
        /// `pyidaungsu_regular.ttf` carries that string — nameID 1 and nameID 6 are
        /// both `Pyidaungsu`, and the only record containing "Regular" is the bare
        /// subfamily. So both call sites (`HistoryViews`, `FeedbackView`) rendered
        /// recognised Mon in Myanmar Sangam MN, whose Mon coverage is thin, with
        /// nothing logged. `MonTextView` used the correct name, which is why the
        /// main result card looked right and these two did not.
        ///
        /// Keep this string equal to nameID 6. Read it back with:
        ///   python3 -c "from fontTools.ttLib import TTFont; \
        ///     print([str(r) for r in TTFont('Fonts/pyidaungsu_regular.ttf')['name'].names if r.nameID==6])"
        ///
        /// `relativeTo: .body` scales it with Dynamic Type from `size` at the
        /// default setting; it was fixed at every setting.
        static func monBody(size: CGFloat = 18) -> Font {
            return Font.custom("Pyidaungsu", size: size, relativeTo: .body)
        }
    }
}

extension Color {
    init(light: Color, dark: Color) {
        self.init(uiColor: UIColor { traitCollection in
            switch traitCollection.userInterfaceStyle {
            case .dark:
                return UIColor(dark)
            default:
                return UIColor(light)
            }
        })
    }
}
