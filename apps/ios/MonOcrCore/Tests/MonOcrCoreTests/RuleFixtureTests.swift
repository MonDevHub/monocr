import Foundation
import Testing

@testable import MonOcrCore

/**
 Checks `LineSegmenter.suppressPageRules` against
 `shared/segmentation-fixtures/rule-cases.json`, the expectations generated from the
 printed-rule specification in `mon_OCR/src/monocr/segmenter.py` and shared with the
 web and Android ports.

 The point of a shared fixture is that three ports cannot drift apart quietly. A
 disagreement here is either a bug in this port or a regenerated fixture, and both
 need a human — do not adjust the expectations to match the code.

 The generator's docstring records two edge cases where the reference's cv2
 morphology deviates from the sentence it implements, and why these expectations
 follow the sentence. `--cross-check` re-derives that classification and fails on any
 divergence it cannot attribute, so a new one cannot hide behind the known two.
 */
struct RuleFixtureTests {

    struct RuleCase: Decodable {
        let name: String
        let width: Int
        let height: Int
        let density: Int
        let rule_rows: [Int]
        let rule_cols: [Int]
        let run_length: Int
        let run_start: Int
        let expected_changed: Bool
        let expected_ink: Int
        let expected_checksum: Int
    }

    struct Fixture: Decodable {
        let rule_span: Float
        let rule_max_ink_share: Float
        let checksum_modulus: Int
        let cases: [RuleCase]
    }

    /// Read from the checkout rather than from a bundle resource: the fixture is
    /// shared with the other two ports and lives outside this package, so copying it
    /// in would create a second copy to drift.
    static func loadFixture() throws -> Fixture {
        let repoRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()  // Tests/MonOcrCoreTests
            .deletingLastPathComponent()  // Tests
            .deletingLastPathComponent()  // MonOcrCore
            .deletingLastPathComponent()  // apps/ios
            .deletingLastPathComponent()  // apps
            .deletingLastPathComponent()  // repository root
        let url = repoRoot.appendingPathComponent("shared/segmentation-fixtures/rule-cases.json")
        return try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: url))
    }

    /// The 32-bit xorshift the generator describes, which every port reproduces.
    ///
    /// A PRNG rather than a literal mask because the cases run to 300x200; the
    /// alternative is 60,000 booleans of JSON per case. It has to be a generator
    /// exactly representable in all four languages, which is why it is not an LCG —
    /// JS numbers lose precision above 2^53 and could not reproduce one.
    static func buildMask(_ c: RuleCase) -> [Bool] {
        var x: UInt32 = 2463534242
        var mask = [Bool](repeating: false, count: c.width * c.height)
        for i in 0..<(c.width * c.height) {
            x ^= x &<< 13
            x ^= x >> 17
            x ^= x &<< 5
            mask[i] = Int(x % 100) < c.density
        }
        for ry in c.rule_rows {
            let length = c.run_length < 0 ? c.width : c.run_length
            let start = c.run_length < 0 ? 0 : c.run_start
            for xx in start..<min(c.width, start + length) { mask[ry * c.width + xx] = true }
        }
        for cx in c.rule_cols {
            for yy in 0..<c.height { mask[yy * c.width + cx] = true }
        }
        return mask
    }

    /// Ink count and a position-weighted checksum.
    ///
    /// A bare count would not notice suppression that removed the right NUMBER of
    /// pixels in the wrong places, which is exactly what an off-by-one in a
    /// run-length scan produces — and what the reference's even-kernel anchor does.
    static func signature(_ mask: [Bool], modulus: Int) -> (Int, Int) {
        var ink = 0
        var sum = 0
        for i in 0..<mask.count where mask[i] {
            ink += 1
            sum = (sum + (i + 1)) % modulus
        }
        return (ink, sum)
    }

    /// The constants are the contract. A fixture generated with different ones is not this one.
    @Test func theFixtureWasGeneratedWithThisPortsConstants() throws {
        let fixture = try Self.loadFixture()
        #expect(fixture.rule_span == LineSegmenter.ruleSpan)
        #expect(fixture.rule_max_ink_share == LineSegmenter.ruleMaxInkShare)
    }

    @Test func everyFixtureCaseMatches() throws {
        let fixture = try Self.loadFixture()
        #expect(!fixture.cases.isEmpty, "the fixture carried no cases")

        for c in fixture.cases {
            var mask = Self.buildMask(c)
            let changed = LineSegmenter.suppressPageRules(&mask, width: c.width, height: c.height)
            let (ink, checksum) = Self.signature(mask, modulus: fixture.checksum_modulus)

            #expect(changed == c.expected_changed, "\(c.name): changed")
            #expect(ink == c.expected_ink, "\(c.name): remaining ink")
            #expect(checksum == c.expected_checksum, "\(c.name): checksum")
        }
    }
}
