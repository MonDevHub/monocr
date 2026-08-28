"""Generate the shared printed-rule-suppression fixture from the canonical implementation.

The expected values here are produced from the SPECIFICATION of printed-rule
suppression, which `mon_OCR/src/monocr/segmenter.py` states as:

    A rule is an unbroken ink run spanning at least `_RULE_SPAN` of the page in
    one direction.

Three ports (web TS, Android Kotlin, iOS Swift) assert against the output, so an
authored number here would silently become the contract.

Generated from the spec rather than from `_suppress_page_rules` itself, which is a
pair of cv2 morphological openings, because the two disagree on two edge cases and
the spec is the one the ports implement. `--cross-check` runs cv2 as well and
classifies every differing pixel; it fails on any difference it cannot attribute to
one of these two, so a NEW divergence cannot hide behind the known ones.

  1. BORDER. `cv2.erode` defaults to a border value of +inf, which for a uint8 mask
     means the pixels outside the image are treated as ink. A run flush against an
     image edge is therefore effectively infinite in that direction and survives the
     opening however short it is: at span 15, a 10px run at x=0 is kept, and the same
     run at x=1 is correctly dropped. On a page cropped tight to its frame this
     removes real marginal ink.

  2. EVEN KERNEL WIDTH. `getStructuringElement` anchors at `ksize / 2`, which is not
     the centre of an even-width kernel, so the opening comes back shifted one pixel
     along that axis: a 14px run at x=2..15 is marked at x=3..16. The rule's first
     pixel survives suppression and the pixel past its end is marked instead. The
     width is `max(15, int(w * 0.5))`, so every page of even width takes this path.

Both make the reference remove the wrong pixels rather than the wrong NUMBER of
pixels, which is why neither showed up in the band-count measurement that justified
the step. Reported for `mon_OCR` to resolve; until it does, the ports follow the
sentence quoted above and this file records exactly where that differs.

Run:
    python3 generate-rule-cases.py rule-cases.json

Add --check to compare against the committed file instead of writing it, and exit 1
on any difference. Add --cross-check to additionally run cv2 and classify the
divergences; that mode needs numpy, cv2 and MON_OCR_SRC pointing at the `src`
directory of a mon_OCR checkout, and plain generation needs none of them.
"""

import argparse
import json
import os
import sys
from pathlib import Path

import numpy as np

# The mask is built by a 32-bit xorshift, chosen because it is exactly
# representable in every target language: JS numbers lose precision above 2^53, so
# an LCG multiplying two 31-bit values could not be reproduced there. Kotlin uses
# UInt, Swift UInt32, TS `>>> 0` after each step.
PRNG_SEED = 2463534242
PRNG_DESCRIPTION = (
    "xorshift32 seeded 2463534242: x ^= x<<13; x ^= x>>17; x ^= x<<5, all mod 2^32. "
    "Pixel i is ink where (x % 100) < density, x taken after the i-th step."
)

CHECKSUM_MODULUS = 1000003


# The two constants are stated here rather than imported so that plain generation
# needs neither cv2 nor a mon_OCR checkout. `--cross-check` asserts they still match
# the reference, so a change upstream cannot drift past this file unnoticed.
RULE_SPAN = 0.5
RULE_MAX_INK_SHARE = 0.8


def span_for(size):
    """The rule span along one axis, in pixels.

    The 15px floor is what stops a small crop using a span inside glyph range:
    on a 20px-wide crop `width * 0.5` is 10px, which a single character can reach.
    """
    return max(15, int(size * RULE_SPAN))


def suppress_page_rules(mask):
    """Remove printed rules from a text mask, per the specification.

    A rule is an unbroken ink run spanning at least `RULE_SPAN` of the page in one
    direction. Implemented as a run-length scan, one sweep per axis, which is what
    the three ports do; see the module docstring for where cv2 morphology differs.

    Returns `(suppressed_mask, changed)` and does not modify the argument.
    """
    height, width = mask.shape
    min_h, min_v = span_for(width), span_for(height)
    rules = np.zeros((height, width), dtype=bool)

    for y in range(height):
        start = 0
        for x in range(width + 1):
            if x < width and mask[y, x]:
                continue
            if x - start >= min_h:
                rules[y, start:x] = True
            start = x + 1

    for x in range(width):
        start = 0
        for y in range(height + 1):
            if y < height and mask[y, x]:
                continue
            if y - start >= min_v:
                rules[start:y, x] = True
            start = y + 1

    ink = int(np.count_nonzero(mask))
    if ink == 0:
        return mask.copy(), False
    rule_ink = int(np.count_nonzero(rules))
    if rule_ink == 0:
        return mask.copy(), False
    if rule_ink > ink * RULE_MAX_INK_SHARE:
        # Found the text. Leaving the page alone is strictly better than emptying
        # it, and the caller is no worse off than before this step existed.
        return mask.copy(), False

    out = mask.copy()
    out[rules] = 0
    return out, True


def cross_check(quiet=False):
    """Run cv2's morphology beside the spec and classify every differing pixel.

    Fails on any difference not attributable to one of the two documented causes.
    A new divergence is the thing this mode exists to catch; the known two are only
    tolerated because they are written down.
    """
    import cv2  # noqa: PLC0415

    src = os.environ.get("MON_OCR_SRC")
    if src:
        path = Path(src).expanduser().resolve()
        if not (path / "monocr" / "segmenter.py").is_file():
            sys.exit(f"MON_OCR_SRC={path} has no monocr/segmenter.py")
        sys.path.insert(0, str(path))
    try:
        from monocr.segmenter import _RULE_MAX_INK_SHARE, _RULE_SPAN  # noqa: PLC0415
    except ImportError as exc:
        sys.exit(
            f"cannot import monocr.segmenter ({exc}). Set MON_OCR_SRC to the src "
            "directory of a mon_OCR checkout, under an interpreter with cv2."
        )
    if (_RULE_SPAN, _RULE_MAX_INK_SHARE) != (RULE_SPAN, RULE_MAX_INK_SHARE):
        sys.exit(
            f"the reference moved: span {_RULE_SPAN} vs {RULE_SPAN}, share "
            f"{_RULE_MAX_INK_SHARE} vs {RULE_MAX_INK_SHARE}. Update this file."
        )

    total_border = total_shift = 0
    for name, w, h, density, rows, cols, run_length, run_start in CASES:
        mask = build_mask(w, h, density, rows, cols, run_length, run_start)
        min_h, min_v = span_for(w), span_for(h)

        spec_rules = np.zeros((h, w), dtype=bool)
        for y in range(h):
            start = 0
            for x in range(w + 1):
                if x < w and mask[y, x]:
                    continue
                if x - start >= min_h:
                    spec_rules[y, start:x] = True
                start = x + 1
        for x in range(w):
            start = 0
            for y in range(h + 1):
                if y < h and mask[y, x]:
                    continue
                if y - start >= min_v:
                    spec_rules[start:y, x] = True
                start = y + 1

        horizontal = cv2.morphologyEx(
            mask, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (min_h, 1))
        )
        vertical = cv2.morphologyEx(
            mask, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (1, min_v))
        )
        cv_rules = cv2.bitwise_or(horizontal, vertical) > 0

        even_axis = min_h % 2 == 0 or min_v % 2 == 0
        differing = np.argwhere(spec_rules != cv_rules)

        def run_touches_an_edge(y, x):
            """Is the pixel part of an ink run that reaches an image edge?

            The border cause is not confined to the edge pixel: cv2 treats the
            outside as ink, so the WHOLE run flush against an edge survives the
            opening. Testing only x == 0 would leave the other 19 pixels of a
            20px edge run looking unexplained.
            """
            if mask[y, x]:
                left = x
                while left > 0 and mask[y, left - 1]:
                    left -= 1
                right = x
                while right < w - 1 and mask[y, right + 1]:
                    right += 1
                if left == 0 or right == w - 1:
                    return True
                top = y
                while top > 0 and mask[top - 1, x]:
                    top -= 1
                bottom = y
                while bottom < h - 1 and mask[bottom + 1, x]:
                    bottom += 1
                if top == 0 or bottom == h - 1:
                    return True
            # A cleared pixel adjacent to an edge-touching run is the same cause.
            return x in (0, w - 1) or y in (0, h - 1)

        if differing.size and not even_axis:
            unexplained = [
                (int(y), int(x)) for y, x in differing if not run_touches_an_edge(int(y), int(x))
            ]
            if unexplained:
                sys.exit(f"{name}: {len(unexplained)} unexplained divergences {unexplained[:6]}")
            total_border += len(differing)
        else:
            total_shift += len(differing)
        if not quiet and differing.size:
            kind = "even-kernel shift" if even_axis else "border"
            print(f"  {name}: {len(differing)} px differ ({kind})")

    print(
        f"cross-check: every divergence attributed "
        f"({total_shift} even-kernel, {total_border} border)"
    )


# name, width, height, density, rule_rows, rule_cols, run_length, run_start
# run_length -1 means the row rule spans the full width; otherwise it is an
# unbroken run of that many pixels starting at run_start, which is how the exact
# boundary either side of the 15px floor gets pinned.
#
# run_start matters independently of length. A run flush against x=0 is where the
# reference's border handling diverges from the spec, so the last two cases pin a
# short run at the edge and the same run one pixel in; the ports must drop both and
# cv2 keeps the first. See the module docstring.
CASES = [
    ("two full-width rules over sparse noise", 100, 100, 5, [10, 50], [], -1, 1),
    ("one row rule and one column rule", 100, 100, 20, [10], [7], -1, 1),
    ("column rules only, dense noise", 100, 100, 60, [], [3, 90], -1, 1),
    ("three adjacent row rules", 40, 90, 15, [5, 6, 7], [], -1, 1),
    ("run of exactly the 15px floor", 20, 20, 30, [5], [], 15, 1),
    ("run one short of the 15px floor", 20, 20, 30, [5], [], 14, 1),
    ("page-sized, framed both axes", 300, 200, 8, [30, 60, 90], [2, 297], -1, 1),
    ("floor governs on a narrow crop", 17, 43, 25, [8], [], 15, 1),
    ("rules on the first row and column", 64, 64, 50, [1], [1], -1, 1),
    ("half-width run on a wide short page", 128, 30, 12, [15], [], 64, 1),
    ("single column rule, tall page", 31, 127, 40, [], [5], -1, 1),
    ("four adjacent row rules, very sparse", 100, 100, 3, [20, 21, 22, 23], [], -1, 1),
    ("short run flush against the left edge", 62, 62, 10, [12], [], 20, 0),
    ("the same short run one pixel inset", 62, 62, 10, [12], [], 20, 1),
]


def build_mask(width, height, density, rule_rows, rule_cols, run_length, run_start):
    """The mask a port must build from the same case description."""
    x = PRNG_SEED
    mask = np.zeros(width * height, dtype=np.uint8)
    for i in range(width * height):
        x ^= (x << 13) & 0xFFFFFFFF
        x ^= x >> 17
        x ^= (x << 5) & 0xFFFFFFFF
        if x % 100 < density:
            mask[i] = 255
    mask = mask.reshape(height, width)

    for row in rule_rows:
        length = width if run_length < 0 else run_length
        start = 0 if run_length < 0 else run_start
        mask[row, start : min(width, start + length)] = 255
    for col in rule_cols:
        mask[:, col] = 255
    return mask


def signature(mask):
    """(ink count, position-weighted checksum) of a mask, flattened row-major.

    A bare ink count would not notice suppression that removed the right NUMBER of
    pixels in the wrong places, which is the failure an off-by-one in a run-length
    scan actually produces.
    """
    flat = mask.reshape(-1)
    on = np.flatnonzero(flat)
    checksum = int((on + 1).sum() % CHECKSUM_MODULUS)
    return int(on.size), checksum


def generate():
    cases = []
    for name, w, h, density, rows, cols, run_length, run_start in CASES:
        mask = build_mask(w, h, density, rows, cols, run_length, run_start)
        after, changed = suppress_page_rules(mask)
        after_ink, checksum = signature(after)
        cases.append(
            {
                "name": name,
                "width": w,
                "height": h,
                "density": density,
                "rule_rows": rows,
                "rule_cols": cols,
                "run_length": run_length,
                "run_start": run_start,
                "expected_changed": bool(changed),
                "expected_ink": after_ink,
                "expected_checksum": checksum,
            }
        )
    return {
        "_comment": (
            "Generated by shared/segmentation-fixtures/generate-rule-cases.py from "
            "the printed-rule specification in mon_OCR monocr/segmenter.py. Do not "
            "hand-edit expected values; regenerate, and verify with --check. "
            "Consumed by the web, Android and iOS printed-rule tests. The generator "
            "docstring records the two edge cases where cv2 morphology in the "
            "reference deviates from the sentence it is implementing."
        ),
        "rule_span": RULE_SPAN,
        "rule_max_ink_share": RULE_MAX_INK_SHARE,
        "prng": PRNG_DESCRIPTION,
        "checksum_modulus": CHECKSUM_MODULUS,
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
    parser.add_argument(
        "--cross-check",
        action="store_true",
        help="also run cv2 morphology and classify every divergence (needs cv2)",
    )
    args = parser.parse_args()

    if args.cross_check:
        cross_check()

    fixture = generate()
    rendered = json.dumps(fixture, indent=2) + "\n"

    if args.check:
        if not args.output.is_file():
            sys.exit(f"{args.output} does not exist, so there is nothing to check")
        committed = args.output.read_text(encoding="utf-8")
        if committed != rendered:
            sys.exit(
                f"{args.output} does not match what the reference produces now. "
                "Either a port changed the fixture by hand or the reference moved; "
                "both need a human."
            )
        print(f"{args.output} matches the reference")
        return

    args.output.write_text(rendered, encoding="utf-8")
    print(f"wrote {args.output} ({len(fixture['cases'])} cases)")


if __name__ == "__main__":
    main()
