import Foundation

/**
 A message for the person using the app, kept as a catalogue key and its
 arguments and turned into text only when it is shown.

 It replaces raw English `String`s in `MainViewModel.errorMessage`. Those had
 two faults: they were never localisable, and `ContentView` decided whether to
 offer CLI help with `errorMessage.contains("CLI")`, which stops working the
 moment the message is translated. `offersCliHelp` is that decision as data.

 Arguments are looked up too, so an argument that is itself a catalogue key
 (a `PageOutcome.error`, for instance) is shown translated. An argument that is
 not a key comes back unchanged.
 */
nonisolated struct UserMessage: Equatable, Sendable {
    let key: String
    let arguments: [String]
    let offersCliHelp: Bool

    init(key: String, arguments: [String] = [], offersCliHelp: Bool = false) {
        self.key = key
        self.arguments = arguments
        self.offersCliHelp = offersCliHelp
    }

    func text(in language: AppLanguage = .current, bundle: Bundle = .main) -> String {
        let filled = arguments.map { AppLocalization.string($0, language: language, bundle: bundle) as CVarArg }
        return AppLocalization.format(key, filled, language: language, bundle: bundle)
    }

    // MARK: - The messages

    /// Every message key, so a test can check each one is in the catalogue.
    static let allKeys: [String] = [
        fileTooLarge.key,
        engineFailed("").key,
        recognitionFailed("").key,
        noTextRead.key,
        pdfUnreadable.key,
        noPdfPageRead.key,
        cancelledNothingSaved.key,
        cancelledPagesKept.key,
        fileOpenFailed("").key,
        imageUnreadable.key,
        attachmentTooLarge.key,
    ]

    static let fileTooLarge = UserMessage(
        key: "File too large (Max 50MB). Use CLI tools or desktop version for bigger file support.",
        offersCliHelp: true)

    static func engineFailed(_ reason: String) -> UserMessage {
        UserMessage(key: "Failed to initialize OCR engine: %@", arguments: [reason])
    }

    static func recognitionFailed(_ reason: String) -> UserMessage {
        UserMessage(key: "Recognition failed: %@", arguments: [reason])
    }

    static let noTextRead = UserMessage(key: "No text could be read.")
    static let pdfUnreadable = UserMessage(key: "Could not access the PDF file.")
    static let noPdfPageRead = UserMessage(key: "No page of this PDF could be read.")
    static let cancelledNothingSaved = UserMessage(key: "Processing cancelled. Nothing was saved.")
    static let cancelledPagesKept = UserMessage(key: "Processing cancelled. The pages read so far are in history.")

    /// The file picker itself failed, before any file was read.
    static func fileOpenFailed(_ reason: String) -> UserMessage {
        UserMessage(key: "Could not open the file: %@", arguments: [reason])
    }

    /// The photo library returned no data, or data that is not an image.
    static let imageUnreadable = UserMessage(key: "This image could not be opened. Try another one.")

    /// A Feedback or Contribute attachment over `SubmissionRules.attachmentPdfMaxBytes`.
    static let attachmentTooLarge = UserMessage(
        key: "This PDF is larger than 20 MB. Choose a smaller file, or send a page as an image.")
}
