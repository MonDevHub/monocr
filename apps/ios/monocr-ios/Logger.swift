import Foundation
import os.log

/**
 * A lightweight, structured logging utility for MonOCR iOS.
 * Utilizes Apple's os.log for performance and system integration.
 *
 * The log file is the one "Export Debug Logs" on the About screen shares, so it
 * must never hold what a person scanned. Two rules keep it that way: debug and
 * info lines reach the file only in DEBUG builds, and os_log marks every message
 * private, so a release build's unified log redacts it unless a debugger is
 * attached. Callers still log counts and sizes, never recognised text.
 */
nonisolated enum MonLogger {
    private static let log = OSLog(subsystem: "mondevhub.monocr", category: "App")
    private static let maxFileSize: UInt64 = 1 * 1024 * 1024 // 1 MB

    #if DEBUG
    static let writesVerboseLinesToFile = true
    #else
    static let writesVerboseLinesToFile = false
    #endif

    static var logFileURL: URL {
        _ = legacyLogRemoved
        return cachesDirectory.appendingPathComponent("monocr_diagnostics.txt")
    }

    private static var cachesDirectory: URL {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
    }

    /// Builds before this file name logged every recognised line to
    /// `monocr_logs.txt` in release builds too. Delete it once per launch so an
    /// export can never carry that text.
    private static let legacyLogRemoved: Void = {
        try? FileManager.default.removeItem(
            at: cachesDirectory.appendingPathComponent("monocr_logs.txt"))
    }()

    private static let dateFormatter: DateFormatter = {
        let df = DateFormatter()
        df.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
        df.locale = Locale(identifier: "en_US_POSIX")
        return df
    }()

    private static func appendToFile(level: String, message: String) {
        let fileURL = logFileURL
        let timestamp = dateFormatter.string(from: Date())
        let logLine = "[\(timestamp)] \(level)/MonOCR: \(message)\n"
        
        guard let data = logLine.data(using: .utf8) else { return }
        
        let fm = FileManager.default
        if fm.fileExists(atPath: fileURL.path) {
            if let attrs = try? fm.attributesOfItem(atPath: fileURL.path),
               let size = attrs[.size] as? UInt64, size > maxFileSize {
                // Rotate
                try? data.write(to: fileURL)
                return
            }
            if let fileHandle = try? FileHandle(forWritingTo: fileURL) {
                fileHandle.seekToEndOfFile()
                fileHandle.write(data)
                fileHandle.closeFile()
            }
        } else {
            try? data.write(to: fileURL)
        }
    }

    nonisolated static func d(_ message: String) {
        os_log("%{private}@", log: log, type: .debug, message)
        if writesVerboseLinesToFile { appendToFile(level: "D", message: message) }
    }

    nonisolated static func i(_ message: String) {
        os_log("%{private}@", log: log, type: .info, message)
        if writesVerboseLinesToFile { appendToFile(level: "I", message: message) }
    }

    nonisolated static func w(_ message: String) {
        os_log("%{private}@", log: log, type: .default, "[W] " + message)
        appendToFile(level: "W", message: message)
    }

    nonisolated static func e(_ message: String, error: Error? = nil) {
        let errorMessage = error != nil ? "\(message) | Error: \(error!.localizedDescription)" : message
        os_log("%{private}@", log: log, type: .error, "[E] " + errorMessage)
        appendToFile(level: "E", message: errorMessage)
    }
}

// Global shorthands for less boilerplate in actor contexts
nonisolated func MonLog_d(_ message: String) { MonLogger.d(message) }
nonisolated func MonLog_i(_ message: String) { MonLogger.i(message) }
nonisolated func MonLog_w(_ message: String) { MonLogger.w(message) }
nonisolated func MonLog_e(_ message: String, error: Error? = nil) { MonLogger.e(message, error: error) }
