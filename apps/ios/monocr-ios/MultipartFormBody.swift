import Foundation

// Everything here is `nonisolated`. The app target compiles with MainActor default
// isolation, which would otherwise infer these onto the main actor — and this code
// writes a 20MB body to disk a byte-buffer at a time. `LineTiler` is marked the same
// way for the same reason.

/**
 The multipart/form-data body `SyncService` uploads, composed independently of the
 stream it is written to.

 Extracted from `SyncService.uploadToFeedbackService`, where it was four closures
 over a captured `OutputStream` inside an `actor` method that also touches
 SwiftData and `URLSession`. Nothing about the bytes needed any of that, and
 keeping them there meant the body was unreachable from the test suite:
 `SyncService.swift` is app-target-only, so the only way to see what this app
 actually sends was to read it. Three defects had been sitting in those four
 closures:

 - `_ = stream.write(...)` in both write helpers. `OutputStream.write` returns the
   byte count it accepted, or -1 on a hard error. Discarding it meant a disk-full
   condition produced a TRUNCATED body which was uploaded anyway and then marked
   `isSynced = true` — the record was destroyed locally in exchange for a partial
   object on the server.
 - `baseAddress!` on the result of `withUnsafeBytes`. A non-nil zero-byte `Data`
   has no base address, so an empty payload trapped instead of writing an empty
   part.
 - `string.data(using: .utf8)!`, which is a force-unwrap of a conversion that
   cannot fail; `Data(string.utf8)` says the same thing without the `!`.

 The sink is a protocol so a test can be the sink: one that collects bytes to
 compare byte-for-byte, one that accepts a single byte per call, and one that
 reports failure part-way through. `OutputStream` conforms with no adapter
 because its `write(_:maxLength:)` already has this shape.
 */
nonisolated protocol MultipartByteSink {
    /// Accepts up to `maxLength` bytes and returns how many it took, or a
    /// negative value on a hard failure.
    func write(_ buffer: UnsafePointer<UInt8>, maxLength len: Int) -> Int

    /// Whatever the sink knows about its last failure, for the error message.
    var sinkError: Error? { get }
}

extension MultipartByteSink {
    nonisolated var sinkError: Error? { nil }
}

nonisolated extension OutputStream: MultipartByteSink {
    nonisolated var sinkError: Error? { streamError }
}

/**
 Why a write stopped, in words a user can act on.

 `SyncService` puts `error.localizedDescription` into `HistoryRecord.syncError`,
 which history renders. "The operation couldn't be completed" — the default for a
 bare `Error` — tells a user nothing, so these carry the numbers that say whether
 the phone ran out of space or the server said no.
 */
nonisolated enum MultipartWriteError: Error, LocalizedError {
    /// The sink refused bytes it had room for, or reported -1.
    case sinkRejectedBytes(attempted: Int, accepted: Int, underlying: Error?)
    /// `withUnsafeBytes` yielded no base address for a buffer claiming a length.
    case unreadableBuffer(byteCount: Int)
    /// The finished file is not the size the body said it wrote.
    case sizeMismatch(intended: Int, onDisk: Int)
    /// The temporary file could not be opened for writing.
    case cannotOpenFile(URL)

    var errorDescription: String? {
        switch self {
        case let .sinkRejectedBytes(attempted, accepted, underlying):
            let reason = underlying?.localizedDescription ?? "no reason reported"
            return "Upload body write failed after \(accepted) of \(attempted) bytes: \(reason). The device may be out of storage."
        case let .unreadableBuffer(byteCount):
            return "Upload body could not read its own \(byteCount)-byte payload."
        case let .sizeMismatch(intended, onDisk):
            return "Upload body is \(onDisk) bytes on disk but \(intended) were written. Nothing was sent."
        case let .cannotOpenFile(url):
            return "Could not open a temporary file for the upload body at \(url.lastPathComponent)."
        }
    }
}

nonisolated struct MultipartFormBody {
    enum Part {
        case field(name: String, value: String)
        case file(name: String, fileName: String, contentType: String, data: Data)
    }

    let boundary: String
    let parts: [Part]

    /**
     The exact body this app sends for one payload of one record.

     `record_id` is the BARE record id and must stay that way: the service builds
     the object key from it (`upload/handler.go` `buildObjectKey`), so a suffix
     here would relocate every object. The per-payload discriminator belongs in
     the `X-Request-ID` header, which `SyncService` sets.
     */
    static func uploadBody(boundary: String,
                           recordId: String,
                           fileName: String,
                           fileType: String,
                           data: Data) -> MultipartFormBody {
        MultipartFormBody(boundary: boundary, parts: [
            .field(name: "record_id", value: recordId),
            .field(name: "original_name", value: fileName),
            .file(name: "file", fileName: fileName, contentType: fileType, data: data),
        ])
    }

    /**
     Write the whole body and return the byte count.

     The caller compares that count against the size of the finished file, which
     is the only check that catches a sink that lied about accepting bytes.
     */
    @discardableResult
    func write(to sink: MultipartByteSink) throws -> Int {
        var total = 0
        for part in parts {
            try Self.write("--\(boundary)\r\n", to: sink, total: &total)
            switch part {
            case let .field(name, value):
                try Self.write("Content-Disposition: form-data; name=\"\(Self.headerSafe(name))\"\r\n\r\n",
                               to: sink, total: &total)
                try Self.write("\(value)\r\n", to: sink, total: &total)
            case let .file(name, fileName, contentType, data):
                try Self.write("Content-Disposition: form-data; name=\"\(Self.headerSafe(name))\"; filename=\"\(Self.headerSafe(fileName))\"\r\n",
                               to: sink, total: &total)
                try Self.write("Content-Type: \(Self.headerSafe(contentType))\r\n\r\n",
                               to: sink, total: &total)
                try Self.write(data, to: sink, total: &total)
                try Self.write("\r\n", to: sink, total: &total)
            }
        }
        try Self.write("--\(boundary)--\r\n", to: sink, total: &total)
        return total
    }

    /**
     Strip what would let a value break out of the header it sits in.

     The filename is user-controlled and was interpolated straight into
     `Content-Disposition: ...; filename="\(fileName)"`. A document named
     `x".txt"\r\nContent-Type: text/html\r\n\r\n...` injected arbitrary multipart
     headers, or an entire extra part, into the request this app makes with its own
     API key. Android carried the same hole.

     CR and LF go because they end a header; the double quote goes because it ends
     the quoted string. Everything else survives, so Mon titles stay intact rather
     than being reduced to underscores.

     Field VALUES are deliberately NOT sanitised, so the service records the name
     the user actually chose. That is safe on a sharper argument than "it is not a
     header": a part body ends only at `\r\n--<boundary>`, so a value carrying CRLF
     is inert unless it also carries the boundary, and the boundary is a fresh UUID
     per request that whoever named the document cannot know. `MultipartFormBodyTests`
     pins that rather than leaving it as an assumption.
     */
    static func headerSafe(_ value: String) -> String {
        value
            .replacingOccurrences(of: "\r", with: "")
            .replacingOccurrences(of: "\n", with: "")
            .replacingOccurrences(of: "\"", with: "'")
    }

    private static func write(_ string: String, to sink: MultipartByteSink, total: inout Int) throws {
        try write(Data(string.utf8), to: sink, total: &total)
    }

    private static func write(_ data: Data, to sink: MultipartByteSink, total: inout Int) throws {
        // A zero-byte part is legitimate and has no base address to pass.
        guard !data.isEmpty else { return }

        try data.withUnsafeBytes { raw in
            guard let base = raw.bindMemory(to: UInt8.self).baseAddress else {
                throw MultipartWriteError.unreadableBuffer(byteCount: data.count)
            }
            // A stream taking fewer bytes than offered is normal and means "call
            // again", so this resumes rather than failing outright — the defect
            // was discarding the count, not the partial write itself. It throws
            // the moment the sink stops making progress (0) or reports an error
            // (-1), which is what a full disk looks like, so no short body can
            // reach `URLSession` claiming to be complete.
            var offset = 0
            while offset < data.count {
                let remaining = data.count - offset
                let accepted = sink.write(base + offset, maxLength: remaining)
                guard accepted > 0 else {
                    throw MultipartWriteError.sinkRejectedBytes(attempted: remaining,
                                                                accepted: accepted,
                                                                underlying: sink.sinkError)
                }
                offset += accepted
            }
        }
        total += data.count
    }
}
