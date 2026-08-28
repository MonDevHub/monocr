import Foundation
import Testing

@testable import MonOcrCore

/**
 Checks `PageNormalizer.dilate` against `shared/segmentation-fixtures/dilate-cases.json`,
 generated from `cv2.getStructuringElement(cv2.MORPH_ELLIPSE, ...)` — the call
 `mon_OCR`'s `_level_background` makes, and therefore the contract.

 This file exists because of a defect in THIS port. Until 2026-08-28 `dilate` used a
 square structuring element while Android used a disk; the two disagreed on 7 of 8
 synthetic pages and every iOS page came out 0.13%-0.34% darker. `PageNormalizerTests`
 was green throughout, because it compared the optimised dilation against a naive one
 that was also a square. Comparing an implementation against a second implementation
 of the same misunderstanding proves only that the misunderstanding is consistent.

 A disagreement here is either a bug in this port or a regenerated fixture, and both
 need a human — do not adjust the expectations to match the code.
 */
struct PageNormalizerFixtureTests {

    struct DilateCase: Decodable {
        let name: String
        let kernel: Int
        let width: Int
        let height: Int
        let expected_checksum: Int
        let expected_sum: Int
    }

    struct Fixture: Decodable {
        let checksum_modulus: Int
        let half_widths: [String: [Int]]
        let cases: [DilateCase]
    }

    static func loadFixture() throws -> Fixture {
        let repoRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()  // Tests/MonOcrCoreTests
            .deletingLastPathComponent()  // Tests
            .deletingLastPathComponent()  // MonOcrCore
            .deletingLastPathComponent()  // apps/ios
            .deletingLastPathComponent()  // apps
            .deletingLastPathComponent()  // repository root
        let url = repoRoot.appendingPathComponent("shared/segmentation-fixtures/dilate-cases.json")
        return try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: url))
    }

    static func source(width: Int, height: Int) -> GreyImage {
        var x: UInt32 = 2463534242
        var px = [UInt8](repeating: 0, count: width * height)
        for i in 0..<(width * height) {
            x ^= x &<< 13
            x ^= x >> 17
            x ^= x &<< 5
            px[i] = UInt8(x % 256)
        }
        return GreyImage(pixels: px, width: width, height: height)
    }

    /// The SHAPE, asserted directly.
    ///
    /// Dilating an image that is black except for one bright centre pixel renders the
    /// structuring element itself. That compares against cv2's own half-widths rather
    /// than against a second copy of this port's formula, which is precisely the
    /// mistake that let a square ship here.
    @Test func theStructuringElementIsCv2sEllipse() throws {
        let fixture = try Self.loadFixture()
        #expect(!fixture.half_widths.isEmpty, "the fixture carried no kernels")

        for (kernelText, expected) in fixture.half_widths {
            let kernel = Int(kernelText)!
            let r = kernel / 2
            let side = 2 * r + 1

            var single = [UInt8](repeating: 0, count: side * side)
            single[r * side + r] = 255
            let rendered = PageNormalizer.dilate(
                GreyImage(pixels: single, width: side, height: side), kernel: kernel)

            for row in 0..<side {
                let on = (0..<side).filter { rendered.pixels[row * side + $0] == 255 }
                if expected[row] < 0 {
                    #expect(on.isEmpty, "kernel \(kernel) row \(row) should be empty")
                    continue
                }
                #expect(on.first == r - expected[row], "kernel \(kernel) row \(row) left edge")
                #expect(on.last == r + expected[row], "kernel \(kernel) row \(row) right edge")
                #expect(on.count == (on.last ?? 0) - (on.first ?? 0) + 1,
                        "kernel \(kernel) row \(row) must be one contiguous run")
            }
        }
    }

    @Test func everyDilationCaseMatchesCv2() throws {
        let fixture = try Self.loadFixture()
        #expect(!fixture.cases.isEmpty, "the fixture carried no cases")

        for c in fixture.cases {
            let out = PageNormalizer.dilate(
                Self.source(width: c.width, height: c.height), kernel: c.kernel)
            var sum = 0
            var checksum = 0
            for i in 0..<out.pixels.count {
                sum += Int(out.pixels[i])
                checksum = (checksum + (i + 1) * Int(out.pixels[i])) % fixture.checksum_modulus
            }
            #expect(checksum == c.expected_checksum, "\(c.name): checksum")
            #expect(sum == c.expected_sum, "\(c.name): sum")
        }
    }
}
