// swift-tools-version: 6.0
import PackageDescription

// Sources/MonOcrCore holds relative symlinks into ../../monocr-ios, not copies.
// The app target is a PBXFileSystemSynchronizedRootGroup over that directory, so
// the files have to stay there for the app to build; symlinking them in lets this
// package test them without the xcodeproj changing at all.
//
// Apple's Command Line Tools ship Testing.framework where SwiftPM does not look,
// so `swift test` needs -F and two -rpath flags to find it. They are NOT set here
// and must not be: SwiftPM generates its own entry-point module for the test
// bundle, that module is guarded by `#if canImport(Testing)`, and target settings
// in this file do not reach it. With the flags here the bundle builds, links, runs
// nothing and exits 0 — a green run with zero tests. Set on the command line they
// reach every module and the tests actually run, so `../Scripts/swift-test.sh`
// passes them, and a bare `swift test` fails loudly with "no such module
// 'Testing'" instead of passing silently.
let package = Package(
    name: "MonOcrCore",
    platforms: [.macOS(.v13), .iOS(.v16)],
    products: [.library(name: "MonOcrCore", targets: ["MonOcrCore"])],
    targets: [
        .target(name: "MonOcrCore", swiftSettings: [.swiftLanguageMode(.v5)]),
        .testTarget(name: "MonOcrCoreTests", dependencies: ["MonOcrCore"],
                    swiftSettings: [.swiftLanguageMode(.v5)]),
    ]
)
