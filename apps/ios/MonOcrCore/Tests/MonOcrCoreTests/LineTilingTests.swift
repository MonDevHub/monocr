import Foundation
import Testing

@testable import MonOcrCore

/**
 Tiling parity with the Python binding.

 `LineTiler` is a port of `tile_line`/`cut_column` in monocr-onnx
 `python/monocr_onnx/segmenter.py`. The whole reason it is worth having is a
 measurement taken with that implementation — on the pinned v3.5 graph, a wide
 line squeezed into the model window scored CER 0.1434 against 0.0795 tiled — so
 a port that cuts somewhere else is not the thing that was measured.

 The cases come from `shared/segmentation-fixtures/tiling-cases.json`, generated
 by running the Python function itself, and web, Android and Rust read the same
 file. Regenerate it with `shared/segmentation-fixtures/generate.py` rather than
 editing a number here.
 */
struct LineTilingTests {

    // MARK: - Fixture

    struct Ink: Decodable {
        let kind: String
        let modulus: Int
    }

    struct TileCase: Decodable {
        let name: String
        let width: Int
        let height: Int
        let ink: Ink
        let expected_tile_widths: [Int]
    }

    struct CutProbe: Decodable {
        let name: String
        let width: Int
        let height: Int
        let ink: Ink
        let x0: Int
        let ideal: Int
        let expected_cut: Int
    }

    struct Fixture: Decodable {
        let target_height: Int
        let target_width: Int
        let cut_search_fraction: Double
        let cut_ink_threshold: Int
        let cases: [TileCase]
        let cut_column_probes: [CutProbe]
    }

    /// A fixture ink rule this port cannot build is a failure, not a skip: the
    /// case would otherwise be silently dropped and the port would look green.
    struct UnknownInkRule: Error, CustomStringConvertible {
        let kind: String
        var description: String { "unknown ink rule '\(kind)'; the generator emitted a pattern this port cannot build" }
    }

    /// Read from the checkout rather than from a bundle resource: the fixture is
    /// shared with the other three ports and lives outside this package, so
    /// copying it in would create a second copy to drift.
    static func loadFixture() throws -> Fixture {
        let repoRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()  // Tests/MonOcrCoreTests
            .deletingLastPathComponent()  // Tests
            .deletingLastPathComponent()  // MonOcrCore
            .deletingLastPathComponent()  // apps/ios
            .deletingLastPathComponent()  // apps
            .deletingLastPathComponent()  // repository root
        let url = repoRoot
            .appendingPathComponent("shared/segmentation-fixtures/tiling-cases.json")
        let data = try Data(contentsOf: url)
        return try JSONDecoder().decode(Fixture.self, from: data)
    }

    /// Rebuild a fixture case's ink rule. Must match the generator exactly.
    static func inkRule(_ kind: String, _ modulus: Int) throws -> (Int) -> Bool {
        switch kind {
        case "mod_eq": return { $0 % modulus == 0 }
        case "mod_ne": return { $0 % modulus != 0 }
        case "solid": return { _ in true }
        case "blank": return { _ in false }
        default: throw UnknownInkRule(kind: kind)
        }
    }

    /// White page with full-height black bars wherever `inked(x)` holds.
    static func page(_ width: Int, _ height: Int, _ inked: (Int) -> Bool) -> GreyImage {
        var pixels = [UInt8](repeating: 255, count: width * height)
        for x in 0..<width where inked(x) {
            for y in 0..<height { pixels[y * width + x] = 0 }
        }
        return GreyImage(pixels: pixels, width: width, height: height)
    }

    static func page(_ testCase: TileCase) throws -> GreyImage {
        page(testCase.width, testCase.height, try inkRule(testCase.ink.kind, testCase.ink.modulus))
    }

    static func page(_ probe: CutProbe) throws -> GreyImage {
        page(probe.width, probe.height, try inkRule(probe.ink.kind, probe.ink.modulus))
    }

    // MARK: - Tests

    /// The constants are the contract. A fixture regenerated with different ones
    /// would compare this port against a function it is not a port of.
    @Test func constantsMatchTheFixture() throws {
        let fixture = try Self.loadFixture()
        #expect(fixture.cut_search_fraction == LineTiler.cutSearchFraction)
        #expect(fixture.cut_ink_threshold == Int(LineTiler.cutInkThreshold))
        #expect(fixture.target_height == ModelWindow.height)
        #expect(fixture.target_width == ModelWindow.width)
    }

    @Test func tileWidthsMatchThePythonBinding() throws {
        let fixture = try Self.loadFixture()
        #expect(!fixture.cases.isEmpty, "the fixture carried no cases")

        for testCase in fixture.cases {
            let tiles = LineTiler.tileLine(
                page: try Self.page(testCase),
                segment: LineSegment(x: 0, y: 0, width: testCase.width, height: testCase.height),
                targetHeight: fixture.target_height,
                targetWidth: fixture.target_width
            )
            #expect(
                tiles.map { $0.width } == testCase.expected_tile_widths,
                "tile widths differ for case '\(testCase.name)'"
            )
        }
    }

    /// The partition property is what stops tiling losing or duplicating text, so
    /// it is asserted on every case rather than on one representative.
    @Test func tilesPartitionTheLineExactly() throws {
        let fixture = try Self.loadFixture()

        for testCase in fixture.cases {
            let tiles = LineTiler.tileLine(
                page: try Self.page(testCase),
                segment: LineSegment(x: 0, y: 0, width: testCase.width, height: testCase.height),
                targetHeight: fixture.target_height,
                targetWidth: fixture.target_width
            )

            var x = 0
            for tile in tiles {
                #expect(tile.x == x, "gap or overlap in case '\(testCase.name)'")
                #expect(tile.width > 0, "empty tile in case '\(testCase.name)'")
                #expect(tile.height == testCase.height, "case '\(testCase.name)' changed height")
                x += tile.width
            }
            #expect(x == testCase.width, "case '\(testCase.name)' did not cover the line")
        }
    }

    /// A wrong tile width does not say whether the cut search or the tiling loop
    /// is at fault. These probes pin the cut search on its own.
    @Test func cutColumnProbes() throws {
        let fixture = try Self.loadFixture()
        #expect(!fixture.cut_column_probes.isEmpty, "the fixture carried no probes")

        for probe in fixture.cut_column_probes {
            let cut = LineTiler.cutColumn(
                page: try Self.page(probe),
                segment: LineSegment(x: 0, y: 0, width: probe.width, height: probe.height),
                x0: probe.x0,
                ideal: probe.ideal,
                cropW: probe.width
            )
            #expect(cut == probe.expected_cut, "cut column differs for probe '\(probe.name)'")
        }
    }

    /// The preprocessor is handed page coordinates, so a line part-way down a
    /// page must not have its tiles reported from the origin.
    @Test func tilesKeepTheSegmentOrigin() throws {
        let fixture = try Self.loadFixture()
        let multiTile = try #require(
            fixture.cases.first { $0.expected_tile_widths.count > 2 },
            "the fixture has no multi-tile case"
        )

        let tiles = LineTiler.tileLine(
            page: try Self.page(multiTile),
            segment: LineSegment(
                x: 40, y: 25, width: multiTile.width - 40, height: multiTile.height - 25
            ),
            targetHeight: fixture.target_height,
            targetWidth: fixture.target_width
        )

        #expect(tiles.first?.x == 40)
        #expect(tiles.allSatisfy { $0.y == 25 })
    }

    /// cutColumn can only return a value in (x0, ideal], but the guard behind
    /// that is structural. A one-pixel-wide tall line is the degenerate input.
    @Test func pathologicalLineStillTerminates() {
        let tiles = LineTiler.tileLine(
            page: Self.page(4, 4000, { _ in true }),
            segment: LineSegment(x: 0, y: 0, width: 4, height: 4000),
            targetHeight: ModelWindow.height,
            targetWidth: ModelWindow.width
        )

        #expect(!tiles.isEmpty)
        #expect(tiles.allSatisfy { $0.width >= 1 })
        #expect(tiles.reduce(0) { $0 + $1.width } == 4)
    }

    /// An empty segment cannot be tiled, and must come back unchanged rather
    /// than as an empty list that would silently drop the line.
    @Test func degenerateSegmentsComeBackUnchanged() {
        let image = Self.page(10, 10, { _ in false })
        for segment in [
            LineSegment(x: 0, y: 0, width: 0, height: 10),
            LineSegment(x: 0, y: 0, width: 10, height: 0),
        ] {
            let tiles = LineTiler.tileLine(
                page: image,
                segment: segment,
                targetHeight: ModelWindow.height,
                targetWidth: ModelWindow.width
            )
            #expect(tiles.count == 1)
            #expect(tiles.first?.width == segment.width)
        }
    }
}
