import Foundation
import Testing

@testable import MonOcrCore

/**
 The upload body `SyncService` sends, checked byte-for-byte.

 There were no tests over this at all, because the body lived in four closures
 inside an `actor` method in `SyncService.swift`, which is app-target-only and
 unreachable from this package. That is the reason the three defects below shipped
 together and stayed:

 - Every write's return value was discarded, so a full disk truncated the body and
   the truncated body was uploaded and marked synced.
 - `baseAddress!` trapped on an empty payload.
 - The filename went into `Content-Disposition` unescaped until it did not, and
   nothing pinned it afterwards.

 A byte-exact assertion is the point rather than "contains the filename": the
 service parses CRLFs, and a body that is merely plausible is what the discarded
 return values already produced.
 */
struct MultipartFormBodyTests {

    /// A sink a test can interrogate: collects bytes, and can be told to accept
    /// them in small pieces or to fail part-way.
    final class CollectingSink: MultipartByteSink {
        private(set) var written = Data()
        private(set) var callCount = 0

        /// Most bytes to accept per call. `OutputStream` is allowed to do this.
        var chunkLimit: Int = .max
        /// Return -1 once this many bytes have been accepted.
        var failAfterBytes: Int?
        /// Accept nothing, ever, without reporting an error.
        var stallsForever = false
        var reportedError: Error?

        var sinkError: Error? { reportedError }

        func write(_ buffer: UnsafePointer<UInt8>, maxLength len: Int) -> Int {
            callCount += 1
            if stallsForever { return 0 }
            if let failAfterBytes, written.count >= failAfterBytes { return -1 }
            let take = min(len, chunkLimit)
            written.append(buffer, count: take)
            return take
        }
    }

    static func text(_ sink: CollectingSink) -> String {
        String(decoding: sink.written, as: UTF8.self)
    }

    static func simpleBody(fileName: String = "note.txt",
                           fileType: String = "text/plain",
                           recordId: String = "REC-1",
                           data: Data = Data("hello".utf8)) -> MultipartFormBody {
        MultipartFormBody.uploadBody(boundary: "B",
                                     recordId: recordId,
                                     fileName: fileName,
                                     fileType: fileType,
                                     data: data)
    }

    static let expectedSimpleBody = [
        "--B\r\n",
        "Content-Disposition: form-data; name=\"record_id\"\r\n\r\n",
        "REC-1\r\n",
        "--B\r\n",
        "Content-Disposition: form-data; name=\"original_name\"\r\n\r\n",
        "note.txt\r\n",
        "--B\r\n",
        "Content-Disposition: form-data; name=\"file\"; filename=\"note.txt\"\r\n",
        "Content-Type: text/plain\r\n\r\n",
        "hello\r\n",
        "--B--\r\n",
    ].joined()

    @Test func bodyIsByteForByteWhatTheServiceParses() throws {
        let sink = CollectingSink()
        let count = try Self.simpleBody().write(to: sink)

        #expect(Self.text(sink) == Self.expectedSimpleBody)
        #expect(count == Data(Self.expectedSimpleBody.utf8).count)
    }

    /**
     The `record_id` field is the bare id, with no payload suffix on it.

     `X-Request-ID` now carries `<recordId>:file` and `<recordId>:transcription` so
     the two requests one record makes are distinguishable. The form field must NOT
     follow: the service builds the object key from `record_id`
     (`services/feedback/internal/upload/handler.go`, `buildObjectKey`), so a
     suffix here would move every object this app has ever uploaded.
     */
    @Test func recordIdFieldStaysBare() throws {
        let sink = CollectingSink()
        try Self.simpleBody(recordId: "abc-123").write(to: sink)
        let body = Self.text(sink)

        #expect(body.contains("name=\"record_id\"\r\n\r\nabc-123\r\n"))
        #expect(!body.contains("abc-123:file"))
        #expect(!body.contains("abc-123:transcription"))
    }

    /**
     An empty payload writes an empty part.

     `data.withUnsafeBytes { ... baseAddress! }` traps here: `Data()` is non-nil
     and has no base address. A record with a zero-byte file is an odd but reachable
     state, and a crash in the sync actor is not a recoverable one.
     */
    @Test func anEmptyPayloadWritesAnEmptyPartRatherThanTrapping() throws {
        let sink = CollectingSink()
        let count = try Self.simpleBody(data: Data()).write(to: sink)
        let body = Self.text(sink)

        #expect(body.hasSuffix("Content-Type: text/plain\r\n\r\n\r\n--B--\r\n"))
        #expect(count == Data(body.utf8).count)
    }

    /**
     A filename cannot open a header or a part of its own.

     The payload below is the one that worked: a document named
     `x".txt"\r\nContent-Type: text/html\r\n\r\n<script>` closed the quoted string,
     ended the `Content-Disposition` line, and appended headers of its own to a
     request carrying this app's API key.
     */
    @Test func aFilenameCannotInjectHeaders() throws {
        let sink = CollectingSink()
        let hostile = "x\".txt\"\r\nContent-Type: text/html\r\n\r\n<script>"
        try Self.simpleBody(fileName: hostile).write(to: sink)

        // Asserted whole rather than by `contains`, because the interesting part is
        // what is NOT there and a substring check cannot see an extra header.
        let expected = [
            "--B\r\n",
            "Content-Disposition: form-data; name=\"record_id\"\r\n\r\n",
            "REC-1\r\n",
            "--B\r\n",
            "Content-Disposition: form-data; name=\"original_name\"\r\n\r\n",
            hostile + "\r\n",
            "--B\r\n",
            "Content-Disposition: form-data; name=\"file\"; filename=\"x'.txt'Content-Type: text/html<script>\"\r\n",
            "Content-Type: text/plain\r\n\r\n",
            "hello\r\n",
            "--B--\r\n",
        ].joined()
        #expect(Self.text(sink) == expected)
    }

    /**
     A hostile filename cannot forge a part, either.

     `original_name` keeps the RAW name on purpose, so the service records what the
     user actually called the file — which means its value does carry the attacker's
     CRLFs. That is safe for a reason worth stating rather than assuming: a part
     body ends only at `\r\n--<boundary>`, and the boundary is a fresh UUID per
     request that the person naming a document cannot know. A delimiter guessed
     wrong is inert data, so the count below stays at one opener per part.
     */
    @Test func aFilenameCannotForgeAPartBoundary() throws {
        let boundary = "Boundary-\(UUID().uuidString)"
        let forged = "x\r\n--Boundary-GUESSED\r\nContent-Disposition: form-data; name=\"file\"; filename=\"evil\"\r\n\r\nEVIL"
        let sink = CollectingSink()
        try MultipartFormBody.uploadBody(boundary: boundary,
                                         recordId: "REC-1",
                                         fileName: forged,
                                         fileType: "text/plain",
                                         data: Data("hello".utf8)).write(to: sink)
        let body = Self.text(sink)

        // Three real openers: two mid-body plus the closing delimiter. The forged
        // one is in there as bytes and is not one of them.
        #expect(body.components(separatedBy: "\r\n--\(boundary)").count == 4)
        #expect(body.contains("--Boundary-GUESSED"))
        #expect(body.hasPrefix("--\(boundary)\r\n"))
    }

    /// A content type is escaped on the same grounds; it reaches the header too.
    @Test func aContentTypeCannotInjectHeaders() throws {
        let sink = CollectingSink()
        try Self.simpleBody(fileType: "text/plain\r\nX-Injected: yes").write(to: sink)

        #expect(Self.text(sink).contains("Content-Type: text/plainX-Injected: yes\r\n\r\n"))
    }

    /**
     Mon survives intact.

     Android sent every Mon filename through `DataOutputStream.writeBytes`, which
     writes the low byte of each char, so U+1000-U+109F arrived as unrelated
     Latin-1. Mon titles are the common case for this app, so this pins the
     encoding rather than trusting it.
     */
    @Test func monFilenamesAreWrittenAsUtf8() throws {
        let sink = CollectingSink()
        let name = "ဗော်ဗိာ်.pdf"
        try Self.simpleBody(fileName: name).write(to: sink)

        #expect(Self.text(sink).contains("filename=\"\(name)\""))
        #expect(sink.written.range(of: Data(name.utf8)) != nil)
    }

    /**
     A sink that accepts one byte per call still receives the whole body.

     `OutputStream.write` returning less than it was offered is normal and means
     "call again". The old code returned after one call and moved on, so a stream
     in that state produced a body missing everything after the first short write.
     */
    @Test func aShortWritingSinkStillGetsEveryByte() throws {
        let sink = CollectingSink()
        sink.chunkLimit = 1
        let count = try Self.simpleBody().write(to: sink)

        #expect(Self.text(sink) == Self.expectedSimpleBody)
        #expect(count == Data(Self.expectedSimpleBody.utf8).count)
        #expect(sink.callCount > Self.expectedSimpleBody.count)
    }

    /**
     A sink that fails throws instead of returning a byte count.

     This is the disk-full path. It used to return the full intended count with a
     partial file behind it, which `SyncService` uploaded and then marked
     `isSynced = true` — the local record deleted in exchange for a truncated
     object.
     */
    @Test func aFailingSinkThrowsRatherThanReportingSuccess() throws {
        let sink = CollectingSink()
        sink.failAfterBytes = 40
        sink.reportedError = CocoaError(.fileWriteOutOfSpace)

        #expect(throws: MultipartWriteError.self) {
            try Self.simpleBody(data: Data(repeating: 0x41, count: 4096)).write(to: sink)
        }
        #expect(sink.written.count < 4096)
    }

    /// A sink that accepts nothing is a failure, not a loop to spin in.
    @Test func aStalledSinkThrowsInsteadOfSpinning() throws {
        let sink = CollectingSink()
        sink.stallsForever = true

        #expect(throws: MultipartWriteError.self) {
            try Self.simpleBody().write(to: sink)
        }
        #expect(sink.callCount == 1)
    }

    /**
     The reported count is the number `SyncService` checks the file size against.

     That comparison is the only thing standing between a truncated body and an
     upload, so a count that drifts from the bytes handed to the sink would defeat
     it silently.
     */
    @Test func reportedCountMatchesTheBytesTheSinkReceived() throws {
        for payloadSize in [0, 1, 3, 4096, 65_537] {
            let sink = CollectingSink()
            sink.chunkLimit = 997
            let count = try Self.simpleBody(data: Data(repeating: 0x7A, count: payloadSize)).write(to: sink)

            #expect(count == sink.written.count)
        }
    }
}
