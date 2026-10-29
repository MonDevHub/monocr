import Foundation

/**
 The languages the in-app picker offers, and how a string is looked up in one.

 WHY THIS EXISTS. iOS has no Mon system language, so the picker in the side menu
 is the only way to get a Mon interface. The picker used to work only by setting
 `\.locale` on the SwiftUI environment. That reaches `Text("key")`, and nothing
 else: every `NSLocalizedString` reads the bundle's preferred localisations,
 which follow the *system* language. So with Mon or Burmese picked in the app,
 the status bar, the segmentation labels and every error stayed in English.

 Strings that are not a SwiftUI literal go through `AppLocalization.string`,
 which opens the `.lproj` folder for the picked language directly.
 */
nonisolated enum AppLanguage: String, CaseIterable, Identifiable, Sendable {
    case english = "en"
    case burmese = "my"
    case mon = "mnw"

    var id: String { rawValue }

    /// The `@AppStorage` key the picker writes.
    static let preferenceKey = "language_pref"

    /// An unknown or missing preference is English, which is what the picker
    /// defaulted to before this type existed.
    init(preference: String?) {
        self = AppLanguage(rawValue: preference ?? "") ?? .english
    }

    /// The language picked in the app, read from the same defaults the picker writes.
    static var current: AppLanguage {
        AppLanguage(preference: UserDefaults.standard.string(forKey: preferenceKey))
    }

    /// The name a speaker of the language reads as their own, so someone who
    /// cannot read the current interface language can still find theirs.
    ///
    /// Not translated here. These are the existing human translations from
    /// `Localizable.xcstrings`: the Burmese entry of "Burmese" and the Mon entry
    /// of "Mon", each followed by the English name the catalogue already carries.
    var endonym: String {
        switch self {
        case .english: return "English"
        case .burmese: return "ဗမာ (Burmese)"
        case .mon: return "ဘာသာမန် (Mon)"
        }
    }

    var locale: Locale { Locale(identifier: rawValue) }
}

nonisolated enum AppLocalization {
    /// A value no catalogue entry can hold, so a miss is told apart from a hit
    /// whose translation happens to equal the key.
    private static let missing = "\u{0}monocr.missing\u{0}"

    /// `key` in `language`, else in English, else the key itself.
    ///
    /// The English step matters for keys that are identifiers rather than
    /// sentences (`main_scanning`): an untranslated Mon entry must fall back to
    /// "Scanning...", not show the identifier.
    static func string(
        _ key: String,
        language: AppLanguage = .current,
        bundle: Bundle = .main,
        table: String? = nil
    ) -> String {
        var candidates = [language]
        if language != .english { candidates.append(.english) }
        for candidate in candidates {
            guard let path = bundle.path(forResource: candidate.rawValue, ofType: "lproj"),
                  let localized = Bundle(path: path) else { continue }
            let value = localized.localizedString(forKey: key, value: missing, table: table)
            if value != missing { return value }
        }
        let value = bundle.localizedString(forKey: key, value: missing, table: table)
        return value == missing ? key : value
    }

    /// `key` looked up as a format string, then filled with `arguments`.
    static func format(
        _ key: String,
        _ arguments: [CVarArg],
        language: AppLanguage = .current,
        bundle: Bundle = .main
    ) -> String {
        let format = string(key, language: language, bundle: bundle)
        guard !arguments.isEmpty else { return format }
        return String(format: format, arguments: arguments)
    }
}
