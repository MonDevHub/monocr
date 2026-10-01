import Testing
@testable import MonOcrCore

struct AdaptiveThresholdTests {
    // Direct neighborhood enumeration is independent of rolling-sum bookkeeping.
    private func reference(_ pixels: [UInt8], width: Int, height: Int) -> [Bool] {
        pixels.indices.map { index in
            let x = index % width
            let y = index / width
            var sum: Int64 = 0
            var count = 0
            for yy in max(0, y - 12)...min(height - 1, y + 12) {
                for xx in max(0, x - 12)...min(width - 1, x + 12) {
                    sum += Int64(pixels[yy * width + xx])
                    count += 1
                }
            }
            return Float(pixels[index]) < Float(sum) / Float(count) - 8
        }
    }

    @Test func rollingThresholdMatchesClippedNeighborhoods() {
        let dimensions = [(1, 1), (1, 53), (53, 1), (12, 12), (25, 25), (26, 27), (63, 35)]
        for (width, height) in dimensions {
            let pixels: [UInt8] = (0..<(width * height)).map { i in
                UInt8((i * 73 + (i / width) * 19 + (i % width) * (i % width) * 11) % 256)
            }
            #expect(LineSegmenter.adaptiveThreshold(pixels, width: width, height: height) ==
                reference(pixels, width: width, height: height))
        }
    }

    @Test func uniformPagesHaveNoAdaptiveInk() {
        for value: UInt8 in [0, 255] {
            let pixels = [UInt8](repeating: value, count: 31 * 29)
            #expect(LineSegmenter.adaptiveThreshold(pixels, width: 31, height: 29) ==
                [Bool](repeating: false, count: pixels.count))
        }
    }

    @Test func thresholdComparisonStaysStrict() {
        var pixels = [UInt8](repeating: 100, count: 25)
        pixels[12] = 92
        pixels[0] = 108
        #expect(!LineSegmenter.adaptiveThreshold(pixels, width: 25, height: 1)[12])
        pixels[12] = 91
        pixels[0] = 109
        #expect(LineSegmenter.adaptiveThreshold(pixels, width: 25, height: 1)[12])
    }
}
