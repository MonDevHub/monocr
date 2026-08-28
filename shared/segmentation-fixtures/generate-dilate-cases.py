"""Generate the shared background-dilation fixture from cv2 itself.

`PageNormalizer` estimates the page background by dilating a downsampled copy, and
the reference (`mon_OCR/src/monocr/utils.py`, `_level_background`) asks cv2 for
`cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))`. The shape of that kernel is
the contract; the expectations here come from running cv2, never from either port.

This file exists because of a defect it would have caught. Until 2026-08-28 the iOS
port dilated with a SQUARE while Android used a disk, so the two disagreed on 7 of 8
synthetic pages and every iOS page came out 0.13%-0.34% darker. Both sides had tests
and neither could see it: iOS compared its optimised dilation against a naive one
that was also a square, which pins the optimisation and never asks what shape is
being optimised. A fixture generated from cv2 asks exactly that.

cv2's ellipse is NOT the geometric disk `dx^2 + dy^2 <= r^2`. That predicate
disagrees with cv2 at every kernel size tested here. It is the per-row half-width
`round(sqrt(r^2 - dy^2))`, which agrees at all of them.

Run:
    /path/to/mon_OCR/.venv/bin/python3 generate-dilate-cases.py dilate-cases.json

Add --check to compare against the committed file instead of writing it, and exit 1
on any difference. Needs cv2 and numpy; the ports that consume the file need neither.
"""

import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np

# The same xorshift32 the rule fixture uses, for the same reason: it is exactly
# representable in Kotlin (UInt), Swift (UInt32) and TS (`>>> 0` after each step),
# where an LCG multiplying two 31-bit values is not, because JS numbers lose
# precision above 2^53.
PRNG_SEED = 2463534242
PRNG_DESCRIPTION = (
    "xorshift32 seeded 2463534242: x ^= x<<13; x ^= x>>17; x ^= x<<5, all mod 2^32. "
    "Pixel i is `x % 256`, x taken after the i-th step."
)
CHECKSUM_MODULUS = 1000003

# kernel, width, height. Kernel sizes are the ones `levelBackground` can actually
# produce: `max(7, (smallH / 4) | 1)`, so 7 is the floor and the rest are odd.
CASES = [
    (7, 23, 19),
    (7, 31, 31),
    (9, 23, 19),
    (9, 31, 31),
    (11, 23, 19),
    (11, 31, 31),
    (15, 23, 19),
    (15, 31, 31),
    # Kernel wider than the image on one axis, where the clipping matters.
    (15, 9, 40),
    (21, 40, 9),
    # A single row and a single column, which is where an axis loop off by one shows.
    (7, 30, 1),
    (7, 1, 30),
]


def build_source(width, height):
    x = PRNG_SEED
    px = np.zeros(width * height, dtype=np.uint8)
    for i in range(width * height):
        x ^= (x << 13) & 0xFFFFFFFF
        x ^= x >> 17
        x ^= (x << 5) & 0xFFFFFFFF
        px[i] = x % 256
    return px.reshape(height, width)


def signature(arr):
    """Position-weighted checksum. A bare sum would not notice a dilation that
    produced the right total in the wrong places, which is what a mis-shaped kernel
    does."""
    flat = arr.reshape(-1).astype(np.int64)
    return int((np.arange(1, flat.size + 1) * flat).sum() % CHECKSUM_MODULUS)


def structuring_element_rows(kernel):
    """cv2's ellipse, described as one half-width per row.

    Emitted into the fixture so a port can assert the SHAPE directly rather than
    only the dilation result, and so a reader can see what the contract is without
    running cv2.
    """
    element = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (kernel, kernel))
    radius = kernel // 2
    rows = []
    for i in range(kernel):
        on = np.flatnonzero(element[i])
        if on.size == 0:
            rows.append(-1)
            continue
        half = int(max(abs(int(on[0]) - radius), abs(int(on[-1]) - radius)))
        # Every cv2 ellipse row is a contiguous run centred on the middle column.
        assert list(on) == list(range(radius - half, radius + half + 1)), (
            f"kernel {kernel} row {i} is not a centred contiguous run: {on.tolist()}"
        )
        rows.append(half)
    return rows


# `_level_background` picks its kernel as `max(7, (small_h // 4) | 1)` and
# `to_normalized_grayscale` samples corner patches of `max(3, h // 10)`. Both floors
# are load-bearing on small inputs and neither was pinned by any port's tests: a
# mutation dropping the kernel floor from 7 to 3, and one dropping the corner floor
# from 3 to 1, both survived Android's whole suite on 2026-08-28.
#
# Evaluated here rather than restated, so the fixture carries the reference's answers
# and a port asserts against those instead of against its own copy of the formula.
KERNEL_RULE_HEIGHTS = [1, 2, 4, 8, 16, 22, 27, 28, 29, 40, 64, 100, 201]
CORNER_RULE_SIDES = [1, 2, 3, 4, 10, 25, 29, 30, 31, 40, 100, 1000]


def reference_kernel(small_height):
    return max(7, (small_height // 4) | 1)


def reference_corner_patch(side):
    return max(3, side // 10)


def generate():
    cases = []
    for kernel, width, height in CASES:
        src = build_source(width, height)
        dilated = cv2.dilate(src, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (kernel, kernel)))
        cases.append(
            {
                "name": f"k{kernel}-{width}x{height}",
                "kernel": kernel,
                "width": width,
                "height": height,
                "expected_checksum": signature(dilated),
                "expected_sum": int(dilated.reshape(-1).astype(np.int64).sum()),
            }
        )
    kernels = sorted({k for k, _, _ in CASES})
    return {
        "_comment": (
            "Generated by shared/segmentation-fixtures/generate-dilate-cases.py from "
            "cv2.getStructuringElement(cv2.MORPH_ELLIPSE, ...) and cv2.dilate, which "
            "is what mon_OCR's _level_background uses. Do not hand-edit expected "
            "values; regenerate, and verify with --check. Consumed by the Android and "
            "iOS PageNormalizer tests. Web does not level backgrounds at all and "
            "records why in apps/web/src/lib/segmentation.ts."
        ),
        "prng": PRNG_DESCRIPTION,
        "checksum_modulus": CHECKSUM_MODULUS,
        "half_widths": {str(k): structuring_element_rows(k) for k in kernels},
        "kernel_for_small_height": {
            str(h): reference_kernel(h) for h in KERNEL_RULE_HEIGHTS
        },
        "corner_patch_for_side": {
            str(s): reference_corner_patch(s) for s in CORNER_RULE_SIDES
        },
        "cases": cases,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument(
        "--check",
        action="store_true",
        help="compare against the committed file and exit 1 on any difference",
    )
    args = parser.parse_args()

    rendered = json.dumps(generate(), indent=2) + "\n"
    if args.check:
        if not args.output.is_file():
            sys.exit(f"{args.output} does not exist, so there is nothing to check")
        if args.output.read_text(encoding="utf-8") != rendered:
            sys.exit(
                f"{args.output} does not match what cv2 produces now. Either a port "
                "edited the fixture by hand or the reference moved; both need a human."
            )
        print(f"{args.output} matches cv2")
        return
    args.output.write_text(rendered, encoding="utf-8")
    print(f"wrote {args.output}")


if __name__ == "__main__":
    main()
