import Foundation
import Testing

@testable import MonOcrCore

/**
 Checks `LineSegmenter.mergeRuns` against
 `shared/segmentation-fixtures/merge-cases.json`, the expectations generated from the
 SPECIFICATION of the merge and shared with the web, Android and Rust ports.

 `mergeRuns` is the only thing standing between raw-profile boundary detection and a
 22x garbage regression, and it now exists ten times in five languages. Parity between
 those ten was checked once, by hand. This file is the permanent version of that
 check.

 The expectations are NOT taken from any port — the generator reimplements the four
 decisions from their statement. A fixture whose oracle is one of the implementations
 proves only that they agree with each other, and if two of them are wrong in the same
 way it certifies the bug. The generator additionally fails unless every one of its
 twenty single-decision mutations is killed by some case and every case kills at least
 one, and unless the greedy fold agrees with an independent brute-force enumeration of
 every way to cut the run list into groups.

 A disagreement here is either a bug in this port or a regenerated fixture, and both
 need a human — do not adjust the expectations to match the code.
 */
struct MergeFixtureTests {

    struct MergeCase: Decodable {
        let name: String
        let note: String
        let profile_length: Int
        let profile_fills: [[Float]]
        let runs: [[Int]]
        let max_gap: Int
        let min_line: Int
        let expected: [[Int]]
        let discriminates: [String]
    }

    struct Fixture: Decodable {
        let min_gap_merge: Int
        let min_line_height: Int
        let mutations: [String: String]
        let cases: [MergeCase]
    }

    struct MissingFixture: Error, CustomStringConvertible {
        let path: String
        var description: String {
            "cannot read the shared line-merge fixture at \(path); set "
                + "MONOCR_MERGE_FIXTURE to point at "
                + "monocr-monorepo/shared/segmentation-fixtures/merge-cases.json"
        }
    }

    /// The fixture, from `MONOCR_MERGE_FIXTURE` if it is set and otherwise from the
    /// checkout. Read from the checkout rather than from a bundle resource because
    /// the fixture is shared with three other ports and lives outside this package,
    /// so copying it in would create a second copy to drift.
    ///
    /// A missing fixture throws. A skip would report a green run for a port that
    /// nothing checked, which is the failure mode this whole file exists to remove.
    static func loadFixture() throws -> Fixture {
        let url: URL
        if let override = ProcessInfo.processInfo.environment["MONOCR_MERGE_FIXTURE"] {
            url = URL(fileURLWithPath: override)
        } else {
            let repoRoot = URL(fileURLWithPath: #filePath)
                .deletingLastPathComponent()  // Tests/MonOcrCoreTests
                .deletingLastPathComponent()  // Tests
                .deletingLastPathComponent()  // MonOcrCore
                .deletingLastPathComponent()  // apps/ios
                .deletingLastPathComponent()  // apps
                .deletingLastPathComponent()  // repository root
            url = repoRoot.appendingPathComponent("shared/segmentation-fixtures/merge-cases.json")
        }
        guard let data = try? Data(contentsOf: url) else {
            throw MissingFixture(path: url.path)
        }
        return try JSONDecoder().decode(Fixture.self, from: data)
    }

    /// The row profile a port must build from the same case description.
    ///
    /// Fills are applied IN ORDER and overwrite, which is how a one-row
    /// sub-threshold dip is written over the band it sits inside. Applying them in
    /// any other order gives a different profile and the fixture would not match.
    static func buildProfile(_ c: MergeCase) -> [Float] {
        var hist = [Float](repeating: 0, count: c.profile_length)
        for fill in c.profile_fills {
            let a = Int(fill[0])
            let b = Int(fill[1])
            for y in a..<b { hist[y] = fill[2] }
        }
        return hist
    }

    static func pairs(_ rows: [[Int]]) -> [(Int, Int)] {
        rows.map { ($0[0], $0[1]) }
    }

    /// The constants are the contract. A fixture generated with different ones is not
    /// this one.
    ///
    /// Named `theMergeFixture...` rather than `theFixture...` because
    /// swift-testing's reporter prints bare function names with no suite prefix, and
    /// `RuleFixtureTests` already has a test by the shorter name — grepping the log
    /// for a failure would land on the wrong suite.
    @Test func theMergeFixtureWasGeneratedWithThisPortsConstants() throws {
        let fixture = try Self.loadFixture()
        #expect(fixture.min_gap_merge == LineSegmenter.minGapMerge)
        #expect(fixture.min_line_height == LineSegmenter.minLineHeight)
    }

    @Test func everyMergeFixtureCaseMatches() throws {
        let fixture = try Self.loadFixture()
        #expect(!fixture.cases.isEmpty, "the fixture carried no cases")
        #expect(!fixture.mutations.isEmpty, "the fixture carried no mutation battery")

        for c in fixture.cases {
            let hist = Self.buildProfile(c)
            let got = LineSegmenter.mergeRuns(
                Self.pairs(c.runs), rawHist: hist, maxGap: c.max_gap, minLine: c.min_line
            )
            let want = Self.pairs(c.expected)

            // Exact equality, not a property. Half these cases assert that a merge
            // does NOT happen — a speckle chain that must not fuse, two real lines
            // that must stay apart — and asserting only the positive is what let the
            // speckle-chain defect survive a mutation battery once.
            #expect(
                got.count == want.count && zip(got, want).allSatisfy { $0 == $1 },
                "\(c.name): got \(got), want \(want). \(c.note)"
            )

            // A regenerated fixture cannot quietly bring in padding: the generator
            // refuses to write a case no mutation kills, and this is the
            // consumer-side half of that guard, for a fixture edited by hand instead.
            #expect(!c.discriminates.isEmpty, "\(c.name) discriminates nothing")
        }
    }
}
