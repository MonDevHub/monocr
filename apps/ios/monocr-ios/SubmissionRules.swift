import Foundation

/**
 Size limits for imports, and when the Feedback and Contribute forms may be sent.

 Both forms upload what they hold (see `SyncService`), so both need consent.
 Feedback has always asked for it; Contribute did not, and sent on a tap.
 */
nonisolated enum SubmissionRules {
    /// Images and PDFs read on the main screen.
    static let importMaxBytes: Int64 = 50 * 1024 * 1024
    /// PDFs attached to Feedback or Contribute, which are uploaded.
    static let attachmentPdfMaxBytes: Int64 = 20 * 1024 * 1024

    static func importTooLarge(byteCount: Int64) -> Bool { byteCount > importMaxBytes }
    static func attachmentTooLarge(byteCount: Int64) -> Bool { byteCount > attachmentPdfMaxBytes }

    static func canSubmitContribution(transcription: String, hasAttachment: Bool, consent: Bool) -> Bool {
        consent && (!transcription.isEmpty || hasAttachment)
    }

    static func canSubmitFeedback(correctedText: String, consent: Bool) -> Bool {
        consent && !correctedText.isEmpty
    }
}
