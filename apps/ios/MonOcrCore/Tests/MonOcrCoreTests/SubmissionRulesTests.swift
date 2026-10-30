import Foundation
import Testing

@testable import MonOcrCore

/// When Feedback and Contribute may be sent, and the import size limits. Both
/// forms upload what they hold, so both wait for consent.
struct SubmissionRulesTests {
    /// Contribute uploads what it holds, so it waits for consent like Feedback.
    @Test func aContributionNeedsConsent() {
        #expect(!SubmissionRules.canSubmitContribution(transcription: "text", hasAttachment: true, consent: false))
        #expect(SubmissionRules.canSubmitContribution(transcription: "text", hasAttachment: false, consent: true))
        #expect(SubmissionRules.canSubmitContribution(transcription: "", hasAttachment: true, consent: true))
        #expect(!SubmissionRules.canSubmitContribution(transcription: "", hasAttachment: false, consent: true))
    }

    @Test func feedbackNeedsTextAndConsent() {
        #expect(!SubmissionRules.canSubmitFeedback(correctedText: "text", consent: false))
        #expect(!SubmissionRules.canSubmitFeedback(correctedText: "", consent: true))
        #expect(SubmissionRules.canSubmitFeedback(correctedText: "text", consent: true))
    }

    @Test func sizeLimitsAreInclusiveOfTheirOwnValue() {
        #expect(!SubmissionRules.attachmentTooLarge(byteCount: SubmissionRules.attachmentPdfMaxBytes))
        #expect(SubmissionRules.attachmentTooLarge(byteCount: SubmissionRules.attachmentPdfMaxBytes + 1))
        #expect(!SubmissionRules.importTooLarge(byteCount: SubmissionRules.importMaxBytes))
        #expect(SubmissionRules.importTooLarge(byteCount: SubmissionRules.importMaxBytes + 1))
    }
}
