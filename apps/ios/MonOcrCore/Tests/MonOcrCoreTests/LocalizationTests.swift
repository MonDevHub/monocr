import Foundation
import Testing

@testable import MonOcrCore

/**
 The in-app language picker, and the catalogue the app's own keys must exist in.

 WHY. iOS has no Mon system language, so the side-menu picker is the only route
 to a Mon interface. It used to reach SwiftUI literals only: everything built
 with `NSLocalizedString` followed the system language, so with Mon picked the
 status bar, the segmentation labels and every error stayed English. These tests
 pin the lookup that replaced it, against a throwaway bundle laid out the way a
 built app lays out its `.lproj` folders.

 WHAT THIS CANNOT CHECK. That SwiftUI re-renders when the picker changes, or that
 a translation is right. Only that the lookup goes to the picked language, falls
 back to English rather than to a raw key, and that every key the code builds at
 runtime is in `Localizable.xcstrings`.
 */
struct LocalizationTests {

    /// A bundle directory with one `Localizable.strings` per language.
    private static func makeBundle(_ tables: [String: [String: String]]) throws -> Bundle {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("monocr-l10n-\(UUID().uuidString)", isDirectory: true)
        for (language, entries) in tables {
            let folder = root.appendingPathComponent("\(language).lproj", isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let data = try PropertyListSerialization.data(
                fromPropertyList: entries, format: .binary, options: 0)
            try data.write(to: folder.appendingPathComponent("Localizable.strings"))
        }
        return try #require(Bundle(path: root.path))
    }

    private static let catalogueURL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()  // Tests/MonOcrCoreTests
        .deletingLastPathComponent()  // Tests
        .deletingLastPathComponent()  // MonOcrCore
        .deletingLastPathComponent()  // apps/ios
        .appendingPathComponent("monocr-ios/Localizable.xcstrings")

    private static func catalogue() throws -> [String: Any] {
        let data = try Data(contentsOf: catalogueURL)
        let json = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        return try #require(json["strings"] as? [String: Any])
    }

    private static func translation(_ key: String, _ language: String, in strings: [String: Any]) -> String? {
        let entry = strings[key] as? [String: Any]
        let localizations = entry?["localizations"] as? [String: Any]
        let unit = (localizations?[language] as? [String: Any])?["stringUnit"] as? [String: Any]
        return unit?["value"] as? String
    }

    @Test func thePickedLanguageWinsOverTheSystemLanguage() throws {
        let bundle = try Self.makeBundle([
            "en": ["Engine ready": "Engine ready"],
            "my": ["Engine ready": "MY-ready"],
            "mnw": ["Engine ready": "MNW-ready"],
        ])
        #expect(AppLocalization.string("Engine ready", language: .burmese, bundle: bundle) == "MY-ready")
        #expect(AppLocalization.string("Engine ready", language: .mon, bundle: bundle) == "MNW-ready")
        #expect(AppLocalization.string("Engine ready", language: .english, bundle: bundle) == "Engine ready")
    }

    /// A key that is an identifier, not a sentence, must never reach the screen.
    @Test func anUntranslatedKeyFallsBackToEnglishNotToTheKey() throws {
        let bundle = try Self.makeBundle([
            "en": ["main_scanning": "Scanning..."],
            "mnw": [:],
        ])
        #expect(AppLocalization.string("main_scanning", language: .mon, bundle: bundle) == "Scanning...")
        #expect(AppLocalization.string("not in any table", language: .mon, bundle: bundle) == "not in any table")
    }

    @Test func anUnknownPreferenceIsEnglish() {
        #expect(AppLanguage(preference: nil) == .english)
        #expect(AppLanguage(preference: "fr") == .english)
        #expect(AppLanguage(preference: "mnw") == .mon)
    }

    @Test func messagesAndTheirArgumentsAreShownInThePickedLanguage() throws {
        let bundle = try Self.makeBundle([
            "en": [:],
            "my": [
                "Recognition failed: %@": "MY-failed: %@",
                "No text could be read.": "MY-no-text",
            ],
        ])
        let message = UserMessage.recognitionFailed(UserMessage.noTextRead.key)
        #expect(message.text(in: .burmese, bundle: bundle) == "MY-failed: MY-no-text")
        // An argument that is not a key, such as a system error, passes through.
        #expect(UserMessage.recognitionFailed("disk full").text(in: .burmese, bundle: bundle)
                == "MY-failed: disk full")
    }

    /// `ContentView` offered CLI help when the message text contained "CLI",
    /// which a translation breaks. Only the over-size message offers it.
    @Test func onlyTheOverSizeMessageOffersCliHelp() {
        #expect(UserMessage.fileTooLarge.offersCliHelp)
        #expect(!UserMessage.recognitionFailed("x").offersCliHelp)
        #expect(!UserMessage.pdfUnreadable.offersCliHelp)
        #expect(!UserMessage.attachmentTooLarge.offersCliHelp)
    }

    /// History keeps the warnings as data, so a row saved in English is shown in
    /// whichever language is picked later.
    @Test func warningsRenderInThePickedLanguageWithTheirCounts() throws {
        let bundle = try Self.makeBundle([
            "en": [:],
            "mnw": ["%lld line(s) could not be read completely. Check the source image.": "MNW %lld"],
        ])
        let warning = ReviewWarning.linesUnread(3)
        #expect(warning.text(in: .mon, bundle: bundle) == "MNW 3")
        #expect(warning.english == "3 line(s) could not be read completely. Check the source image.")
        #expect(ReviewWarning.unfinished(1, of: 2).english
                == "1 of 2 pages have not been read; this reading is incomplete.")
        #expect(ReviewWarning.pagesUnread([2, 5]).english == "Pages could not be read: 2, 5.")
    }

    @Test func savedMetadataRoundTripsIntoLocalizedWarnings() throws {
        let metadata = OcrReviewMetadata(
            modelVersion: "test",
            pages: [PageOutcome(pageIndex: 0, state: .completed, error: nil)],
            lines: [], looksSoft: true, cancelled: false)
        let decoded = try #require(OcrReviewMetadata.decoded(from: metadata.encoded()))
        let bundle = try Self.makeBundle([
            "en": [:],
            "my": [ReviewWarning.soft.key: "MY-soft"],
        ])
        #expect(decoded.localizedWarningSummary(in: .burmese, bundle: bundle) == "MY-soft")
        #expect(OcrReviewMetadata.decoded(from: nil) == nil)
        #expect(OcrReviewMetadata.decoded(from: "not json") == nil)
    }

    /// Every key the code builds at runtime has to be in the catalogue, or no
    /// translation can ever reach it: a missing key is a string the translators
    /// never see.
    @Test func everyRuntimeKeyIsInTheCatalogue() throws {
        let strings = try Self.catalogue()
        var keys = SegmentationMode.allCases.flatMap { [$0.labelKey, $0.detailKey] }
        keys += UserMessage.allKeys
        keys += ReviewWarning.examples.map(\.key)
        keys += PageOutcome.errorKeys
        let missing = keys.filter { strings[$0] == nil }
        #expect(missing.isEmpty, "not in Localizable.xcstrings: \(missing)")
    }

    /// The picker shows each language in its own script. Those names are taken
    /// from the catalogue's human translations, never written here, so this
    /// fails if the two drift apart.
    @Test func endonymsAreTheCataloguesOwnTranslations() throws {
        let strings = try Self.catalogue()
        #expect(AppLanguage.burmese.endonym == Self.translation("Burmese", "my", in: strings))
        #expect(AppLanguage.mon.endonym == Self.translation("Mon", "mnw", in: strings))
        #expect(AppLanguage.english.endonym == "English")
    }
}
