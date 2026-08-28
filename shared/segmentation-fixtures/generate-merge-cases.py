"""Generate the shared line-merge fixture from the SPECIFICATION of `merge_runs`.

`merge_runs` is the step that pairs with raw-profile boundary detection: raw
detection splits a Mon line wherever one row dips below the gap threshold, which on
the measured page happened between the upper diacritic zone and the consonant
bodies, and the merge is what puts the two halves back. Without it the measured page
went from 5 bands to 110 — a 22x garbage regression. It now exists ten times in five
languages, so nothing but a shared fixture stops the ten drifting apart.

WHY THE EXPECTATIONS ARE NOT TAKEN FROM A PORT
----------------------------------------------
`merge_runs` is reimplemented below from its four stated decisions, not imported
from any of the ten implementations, for the reason `generate-rule-cases.py` argues
at length: a fixture whose oracle is one of the implementations proves only that
they agree with each other. If the Rust version and this file are both wrong in the
same way, the fixture certifies the bug.

Two independent checks run on every generation, neither of them optional:

  1. A BRUTE-FORCE ORACLE (`--cross-check` prints its work; it runs regardless).
     The reference is a left-to-right greedy fold, which is inherently sequential.
     So it is checked against a DECLARATIVE characterisation instead of a second
     fold: enumerate all 2^(n-1) ways to cut the run list into consecutive groups,
     and keep the partitions where every join INSIDE a group satisfies the merge
     predicate and every cut BETWEEN groups fails it. Exactly one partition can
     satisfy that, and it is the fold's output. The enumeration shares no control
     flow with the fold, so an off-by-one in the accumulator is visible to it.

  2. A MUTATION BATTERY (`--audit` prints the matrix; it runs regardless).
     Twenty single-decision variants of the algorithm are run over every case.
     Generation FAILS if any variant produces the committed expectations on every
     case, and it FAILS if any case is killed by no variant. A case that passes
     under every variant is padding, and padding in a parity fixture is worse than
     nothing: it is green that means nothing.

     A per-decision battery is not sufficient on its own, because deviations MASK
     each other — reverting decisions 1 and 4 together was caught by a single case
     until a sibling port's review found it, since the unfiltered median collapses
     `typical` far enough that the loosened fragment clause reproduces the right
     answer anyway. `COMBINATIONS` therefore also runs the one combined deviation
     that five ports were actually in, and requires two independent killers.

THE FOUR DECISIONS THIS FILE PINS
---------------------------------
  1. `typical` is the MEDIAN of run heights, filtered to runs at least `min_line`
     tall, falling back to the unfiltered median when none qualify, floored at 1.
     The filter matters because the merge deliberately runs BEFORE the height
     filter, so `runs` still holds every speckle: measured on a sibling port, 30%
     of collected runs were under the minimum, and on 8 of 55 pages that drove
     `typical` below 10 — one page reached `typical` 2 against a real line height
     of 35, whereupon the ceiling refused every merge and the pass switched itself
     off on exactly the pages that needed it.
  2. `ceiling` is `typical * 2`, and no merge may produce a taller band. Measured:
     without it, page 47 of a 56-page book went from 36 bands to 10, with single
     bands of 534, 632 and 732 rows, losing 92% of its readable characters.
  3. A merge happens when `gap_size <= max_gap` AND (`gap_has_ink` OR `fragment`)
     AND the result fits the ceiling. `gap_has_ink` means EVERY row in the gap is
     above zero, and is vacuously true for an empty gap.
  4. `fragment` is `2 * min(ha, hb) <= typical AND max(ha, hb) >= min_line`. The
     second half is what stops a run of speckle merging with itself: measured on a
     12-speck fixture, twelve 2-row specks fused into one 46-row band, which then
     CLEARS the height filter and is handed to the recogniser as a line.

WHY EVERY CASE CARRIES ORDINARY COMPANION LINES
-----------------------------------------------
A two-run case is degenerate and cannot isolate anything, because with two runs the
median IS one of the two. Three fixtures in `monocr-onnx/rust/src/segmenter.rs` had
that defect and were reported as proof of clauses they did not test: its ink-alone
case had runs of 40/40/82/82, so the median was 82 and `2 * 40 <= 82` fired the
fragment clause too. Every case below whose verdict depends on the median therefore
carries full-height companion lines, and the per-case `note` states the arithmetic.
The mutation battery is what proves the claim rather than the note.

Run:
    python3 generate-merge-cases.py merge-cases.json

Add --check to compare against the committed file instead of writing it, and exit 1
on any difference. Add --audit to print the mutation matrix, --cross-check to print
the brute-force oracle's work. Needs nothing but the standard library, and neither
do the four ports that consume the file.
"""

import argparse
import itertools
import json
import sys
from dataclasses import dataclass, replace
from pathlib import Path

# The reference's own constants, from `monocr-onnx/rust/src/segmenter.rs`
# (`MIN_GAP_MERGE`) and each port's minimum line height. Stamped into the fixture so
# a port can assert that the file was generated with the constants it compiles with;
# a fixture generated with different ones is not this fixture.
MIN_GAP_MERGE = 10
MIN_LINE_HEIGHT = 10


# --------------------------------------------------------------------------------
# The algorithm, with every decision behind a flag so a single decision can be
# altered without touching the reference path. All-defaults IS the specification.
# --------------------------------------------------------------------------------


@dataclass(frozen=True)
class Variant:
    """One deviation from the specification, or none at all."""

    # Decision 0: the fold's own structure, which is not one of the four decisions
    # but is where a transcription error lands.
    seed_first_run: bool = True
    # Decision 1: typical
    empty_guard: bool = True
    filter_median_by_min_line: bool = True
    fallback_unfiltered_median: bool = True
    typical_from: str = "median"  # or "max"
    median_lower_index: bool = False  # (n-1)//2 instead of n//2 on an even count
    floor_typical_at_one: bool = True
    # Decision 2: ceiling
    apply_ceiling: bool = True
    ceiling_multiple: int = 2
    ceiling_strict: bool = False  # `<` instead of `<=`
    # Decision 3: the merge conjunction
    apply_size_bound: bool = True
    size_bound_strict: bool = False  # `<` instead of `<=`
    apply_ink_clause: bool = True
    ink_any: bool = False  # ANY row inked instead of EVERY row
    ink_includes_zero: bool = False  # `>= 0` instead of `> 0`
    apply_fragment_clause: bool = True
    # Decision 4: fragment
    fragment_ratio: int = 2
    fragment_uses_max: bool = False  # max(ha,hb) instead of min(ha,hb)
    fragment_min_line_guard: bool = True
    fragment_min_line_strict: bool = False  # `>` instead of `>=`


REFERENCE = Variant()

# name -> the single-decision deviation, and which of the four decisions it attacks.
MUTATIONS = {
    "first_run_dropped": (replace(REFERENCE, seed_first_run=False), 0),
    "no_empty_guard": (replace(REFERENCE, empty_guard=False), 1),
    "median_unfiltered": (replace(REFERENCE, filter_median_by_min_line=False), 1),
    "fallback_to_one": (replace(REFERENCE, fallback_unfiltered_median=False), 1),
    "typical_from_max": (replace(REFERENCE, typical_from="max"), 1),
    "median_lower_index": (replace(REFERENCE, median_lower_index=True), 1),
    "typical_not_floored_at_one": (replace(REFERENCE, floor_typical_at_one=False), 1),
    "no_ceiling": (replace(REFERENCE, apply_ceiling=False), 2),
    "ceiling_three_typical": (replace(REFERENCE, ceiling_multiple=3), 2),
    "ceiling_strict": (replace(REFERENCE, ceiling_strict=True), 2),
    "no_size_bound": (replace(REFERENCE, apply_size_bound=False), 3),
    "size_bound_strict": (replace(REFERENCE, size_bound_strict=True), 3),
    "no_ink_clause": (replace(REFERENCE, apply_ink_clause=False), 3),
    "ink_any_row": (replace(REFERENCE, ink_any=True), 3),
    "ink_includes_zero": (replace(REFERENCE, ink_includes_zero=True), 3),
    "no_fragment_clause": (replace(REFERENCE, apply_fragment_clause=False), 3),
    "fragment_ratio_one": (replace(REFERENCE, fragment_ratio=1), 4),
    "fragment_uses_max": (replace(REFERENCE, fragment_uses_max=True), 4),
    "fragment_without_min_line_guard": (
        replace(REFERENCE, fragment_min_line_guard=False),
        4,
    ),
    "fragment_min_line_strict": (replace(REFERENCE, fragment_min_line_strict=True), 4),
}


def typical_height(runs, min_line, v):
    """Decision 1. The page's own typical line height."""
    heights = [b - a for a, b in runs]
    if v.filter_median_by_min_line:
        qualifying = [h for h in heights if h >= min_line]
        if qualifying:
            heights = qualifying
        elif v.fallback_unfiltered_median:
            pass  # keep the unfiltered heights
        else:
            return 1 if v.floor_typical_at_one else 0
    if v.typical_from == "max":
        value = max(heights)
    else:
        heights = sorted(heights)
        index = (len(heights) - 1) // 2 if v.median_lower_index else len(heights) // 2
        value = heights[index]
    return max(value, 1) if v.floor_typical_at_one else value


def gap_is_inked(hist, gap_start, gap_end, v):
    """Decision 3's ink test. Out-of-range rows carry no ink, as `hist.get` gives."""

    def inked(y):
        if y < 0 or y >= len(hist):
            return False
        return hist[y] >= 0.0 if v.ink_includes_zero else hist[y] > 0.0

    rows = range(gap_start, gap_end)
    if v.ink_any:
        return any(inked(y) for y in rows)
    return all(inked(y) for y in rows)


def may_merge(acc, run, hist, max_gap, min_line, typical, ceiling, v):
    """Decisions 3 and 4: may `run` join the accumulated `acc`?"""
    a0, a1 = acc
    r0, r1 = run

    gap_size = max(0, r0 - a1)
    if v.apply_size_bound:
        if v.size_bound_strict:
            if not gap_size < max_gap:
                return False
        elif not gap_size <= max_gap:
            return False

    ha, hb = a1 - a0, r1 - r0
    side = max(ha, hb) if v.fragment_uses_max else min(ha, hb)
    fragment = v.fragment_ratio * side <= typical
    if v.fragment_min_line_guard:
        if v.fragment_min_line_strict:
            fragment = fragment and max(ha, hb) > min_line
        else:
            fragment = fragment and max(ha, hb) >= min_line

    reasons = []
    if v.apply_ink_clause:
        reasons.append(gap_is_inked(hist, a1, r0, v))
    if v.apply_fragment_clause:
        reasons.append(fragment)
    if not any(reasons):
        return False

    if v.apply_ceiling:
        span = r1 - a0
        if v.ceiling_strict:
            if not span < ceiling:
                return False
        elif not span <= ceiling:
            return False
    return True


def merge_runs(runs, hist, max_gap, min_line, v=REFERENCE):
    """Fuse runs that a single sub-threshold row split apart.

    A left-to-right greedy fold: each run either joins the run being accumulated or
    starts a new one. `typical` is computed once, from the runs AS DETECTED, and
    never from the accumulation — judging a fragment against its neighbour cascades,
    because every merge makes the accumulation taller and a taller accumulation
    makes the next line look more like a fragment.

    `runs` must be ascending and non-overlapping, which is what the run collector
    emits; `evaluate` enforces it on every case and says why.
    """
    if v.empty_guard and not runs:
        return []

    typical = typical_height(runs, min_line, v)
    ceiling = typical * v.ceiling_multiple

    merged = []
    for r0, r1 in runs:
        if merged and may_merge(
            merged[-1], (r0, r1), hist, max_gap, min_line, typical, ceiling, v
        ):
            merged[-1] = (merged[-1][0], r1)
            continue
        if not merged and not v.seed_first_run:
            # A fold written as "join the last run, otherwise start a new one" that
            # forgets the empty case drops the first run silently.
            continue
        merged.append((r0, r1))
    return merged


# --------------------------------------------------------------------------------
# The brute-force oracle.
# --------------------------------------------------------------------------------

# 2^(n-1) partitions, so a case with more runs than this is not enumerated. No case
# in this file reaches it; the guard is here so adding one is a loud failure rather
# than a hang.
MAX_RUNS_FOR_ENUMERATION = 18


def brute_force_partition(runs, hist, max_gap, min_line):
    """The fold's output, derived by enumeration rather than by folding.

    A grouping of consecutive runs is the fold's answer exactly when every join
    inside a group satisfies the merge predicate and every cut between groups fails
    it. Enumerate every grouping and keep the ones that satisfy that; exactly one
    can, and this asserts it.

    This is a different FORMULATION, not a different explanation. It shares no
    control flow with the fold, which is the point: an off-by-one in the accumulator
    is invisible to a second fold written the same way.
    """
    n = len(runs)
    if n == 0:
        return []
    if n > MAX_RUNS_FOR_ENUMERATION:
        raise ValueError(
            f"{n} runs is {2 ** (n - 1)} partitions; raise "
            "MAX_RUNS_FOR_ENUMERATION deliberately or shrink the case"
        )

    typical = typical_height(runs, min_line, REFERENCE)
    ceiling = typical * REFERENCE.ceiling_multiple

    def satisfies(groups):
        for gi, group in enumerate(groups):
            acc = runs[group[0]]
            for idx in group[1:]:
                if not may_merge(
                    acc, runs[idx], hist, max_gap, min_line, typical, ceiling, REFERENCE
                ):
                    return False
                acc = (acc[0], runs[idx][1])
            if gi + 1 < len(groups):
                nxt = runs[groups[gi + 1][0]]
                if may_merge(
                    acc, nxt, hist, max_gap, min_line, typical, ceiling, REFERENCE
                ):
                    return False
        return True

    winners = []
    for cuts in itertools.product([False, True], repeat=n - 1):
        groups, current = [], [0]
        for i, cut in enumerate(cuts):
            if cut:
                groups.append(current)
                current = []
            current.append(i + 1)
        groups.append(current)
        if satisfies(groups):
            winners.append([(runs[g[0]][0], runs[g[-1]][1]) for g in groups])

    if len(winners) != 1:
        raise AssertionError(
            f"the declarative characterisation admits {len(winners)} partitions, "
            f"so it does not characterise the fold: {winners[:3]}"
        )
    return winners[0]


# --------------------------------------------------------------------------------
# The cases.
#
# Each is (name, profile_length, fills, runs, max_gap, min_line, note). `fills` are
# applied in order, so a later one-row fill overwrites the band under it, which is
# how a sub-threshold dip is written.
#
# The `note` is the hand arithmetic. It is documentation only — what actually proves
# a case discriminates something is the mutation battery, which writes the kill list
# into the fixture itself rather than trusting the note.
# --------------------------------------------------------------------------------

CASES = [
    (
        "measured one-row inked dip, ink clause alone",
        600,
        [(260, 325, 200.0), (280, 281, 6.0), (400, 435, 300.0), (460, 495, 300.0),
         (520, 555, 300.0)],
        [(260, 280), (281, 325), (400, 435), (460, 495), (520, 555)],
        MIN_GAP_MERGE,
        MIN_LINE_HEIGHT,
        "The geometry measured on a 300 DPI Mon page: one line at rows 260-324, "
        "split because row 280 carried 6 ink pixels against a threshold of 7.0. "
        "The companions are 35 rows, and that is arithmetic rather than "
        "decoration: heights are 20/44/35/35/35, median 35, so 2*20 > 35 and the "
        "FRAGMENT clause is false — only gap_has_ink can merge this pair. The "
        "merged span is 65 against a ceiling of 70, so the ceiling permits it. At "
        "44-row companions the median rises to 44, 2*20 <= 44 fires, and dropping "
        "the ink clause survives; at 30-row companions the ceiling drops to 60 and "
        "refuses the merge outright. The window is narrow and this is inside it.",
    ),
    (
        "measured two-row empty gap, fragment clause alone",
        700,
        [(341, 360, 40.0), (362, 404, 300.0), (450, 492, 300.0), (520, 562, 300.0),
         (580, 622, 300.0)],
        [(341, 360), (362, 404), (450, 492), (520, 562), (580, 622)],
        MIN_GAP_MERGE,
        MIN_LINE_HEIGHT,
        "The other measured case: rows 341-359 are the upper marks and 362-403 the "
        "body of one line, separated by two rows of genuinely ZERO ink. The ink "
        "clause cannot cross that, so only the fragment clause can merge it. "
        "Heights 19/42/42/42/42, median 42, so 2*19 <= 42 holds and max(19,42) = "
        "42 >= 10 holds; the merged span of 63 fits the 84-row ceiling.",
    ),
    (
        "two full-height lines two rows apart stay separate",
        400,
        [(20, 60, 300.0), (62, 102, 300.0), (180, 240, 300.0), (280, 340, 300.0)],
        [(20, 60), (62, 102), (180, 240), (280, 340)],
        MIN_GAP_MERGE,
        MIN_LINE_HEIGHT,
        "The case the fragment clause must NOT swallow: same 2-row empty gap as "
        "above, but both runs are full lines. Heights 40/40/60/60, median 60, so "
        "2*40 = 80 > 60 and fragment is false. The gap is empty so gap_has_ink is "
        "false. The merged span of 82 fits the 120-row ceiling, so the ceiling is "
        "NOT what refuses this — without the 60-row companions it would be "
        "(median 40, ceiling 80, span 82) and loosening the fragment ratio to 1x "
        "would survive.",
    ),
    (
        "wide gap inked throughout is still a boundary",
        400,
        [(20, 60, 300.0), (60, 75, 5.0), (75, 115, 300.0), (180, 240, 300.0),
         (280, 340, 300.0)],
        [(20, 60), (75, 115), (180, 240), (280, 340)],
        MIN_GAP_MERGE,
        MIN_LINE_HEIGHT,
        "Overlapping diacritics can hold the raw profile above zero right across "
        "real inter-line spacing; upstream that collapsed 3 PDF lines into 1. "
        "gap_has_ink is TRUE across all 15 rows, fragment is false (median 60, "
        "2*40 > 60), and the merged span of 95 fits the 120-row ceiling — so only "
        "the size bound stands in the way.",
    ),
    (
        "gap of exactly max_gap merges",
        400,
        [(20, 60, 300.0), (60, 70, 5.0), (70, 110, 300.0), (180, 240, 300.0),
         (280, 340, 300.0)],
        [(20, 60), (70, 110), (180, 240), (280, 340)],
        MIN_GAP_MERGE,
        MIN_LINE_HEIGHT,
        "The boundary of the size bound, which the case above cannot reach: the "
        "gap is exactly 10 rows and inked throughout, so `<=` merges and `<` does "
        "not. Median 60, so fragment is false and gap_has_ink is the reason; the "
        "merged span of 90 fits the 120-row ceiling.",
    ),
    (
        "a clean break holding some ink does not merge",
        400,
        [(20, 60, 300.0), (60, 61, 5.0), (62, 63, 5.0), (63, 103, 300.0),
         (180, 240, 300.0), (280, 340, 300.0)],
        [(20, 60), (63, 103), (180, 240), (280, 340)],
        MIN_GAP_MERGE,
        MIN_LINE_HEIGHT,
        "A real line boundary that happens to carry ink on 2 of its 3 rows: row 61 "
        "is empty. gap_has_ink asks whether EVERY row is inked, not whether any is, "
        "and this is the only case that can tell those apart. Everything else "
        "permits the merge — gap 3 rows, fragment false at median 60, span 83 "
        "inside the 120-row ceiling.",
    ),
    (
        "ceiling refuses an otherwise-valid merge",
        400,
        [(20, 80, 300.0), (80, 82, 5.0), (82, 142, 300.0), (200, 260, 300.0),
         (300, 360, 300.0)],
        [(20, 80), (82, 142), (200, 260), (300, 360)],
        MIN_GAP_MERGE,
        MIN_LINE_HEIGHT,
        "Every other clause says merge and only the height cap refuses. Two 60-row "
        "runs two rows apart with ink in the gap, on a page whose median run is 60: "
        "gap_size 2 is inside the bound and gap_has_ink is true, so without the cap "
        "this merges. The merged span would be 122 against a ceiling of 120.",
    ),
    (
        "merged span of exactly the ceiling is allowed",
        400,
        [(20, 80, 300.0), (80, 82, 5.0), (82, 140, 300.0), (200, 260, 300.0),
         (300, 360, 300.0)],
        [(20, 80), (82, 140), (200, 260), (300, 360)],
        MIN_GAP_MERGE,
        MIN_LINE_HEIGHT,
        "The boundary of the ceiling, one row inside the case above: heights "
        "60/58/60/60, median 60, ceiling 120, and the merged span is exactly 120. "
        "`<=` allows it and `<` does not, and a legitimate merge of two halves "
        "landing at about one typical line must not be refused.",
    ),
    (
        "fragment attaching to a line of exactly min_line",
        200,
        [(20, 25, 40.0), (27, 37, 300.0), (60, 80, 300.0), (100, 120, 300.0),
         (140, 160, 300.0)],
        [(20, 25), (27, 37), (60, 80), (100, 120), (140, 160)],
        MIN_GAP_MERGE,
        MIN_LINE_HEIGHT,
        "The boundary of the fragment clause's second half. A 5-row mark two empty "
        "rows above a run of exactly 10 rows, which is exactly min_line: `>=` "
        "attaches it and `>` does not. Heights 5/10/20/20/20 filter to "
        "10/20/20/20, median 20, so 2*5 <= 20 holds and the merged span of 17 fits "
        "the 40-row ceiling. The gap is empty, so the fragment clause is the only "
        "route.",
    ),
    (
        "speckle must not set typical on a noisy page",
        400,
        [(0, 2, 20.0), (4, 6, 20.0), (8, 10, 20.0), (12, 14, 20.0), (16, 18, 20.0),
         (20, 22, 20.0), (24, 26, 20.0), (28, 30, 20.0), (32, 34, 20.0),
         (36, 38, 20.0), (40, 42, 20.0), (44, 46, 20.0),
         (100, 124, 300.0), (124, 126, 5.0), (126, 150, 300.0),
         (200, 250, 300.0), (260, 310, 300.0), (320, 370, 300.0)],
        [(0, 2), (4, 6), (8, 10), (12, 14), (16, 18), (20, 22), (24, 26), (28, 30),
         (32, 34), (36, 38), (40, 42), (44, 46),
         (100, 124), (126, 150),
         (200, 250), (260, 310), (320, 370)],
        MIN_GAP_MERGE,
        MIN_LINE_HEIGHT,
        "The merge runs BEFORE the height filter, so `runs` still holds every "
        "speckle the profile picked up. Twelve 2-row specks against five real runs "
        "makes the UNFILTERED median 2 and the ceiling 4, whereupon the split line "
        "at rows 100-149 cannot merge and the pass switches itself off on exactly "
        "the page that needs it. Filtered to runs at least 10 tall the median is "
        "50, the ceiling 100, and the 24 + inked-dip + 24 pair merges to a 50-row "
        "band — one ordinary line by this page's own standard, which is why the "
        "halves are 24 and not 50.",
    ),
    (
        "a speckle chain must not fuse into a line-height band",
        400,
        [(10, 13, 20.0), (15, 18, 20.0), (20, 23, 20.0), (25, 28, 20.0),
         (30, 33, 20.0), (35, 38, 20.0), (40, 43, 20.0), (45, 48, 20.0),
         (50, 53, 20.0), (55, 58, 20.0), (60, 63, 20.0), (65, 68, 20.0),
         (70, 73, 20.0), (75, 78, 20.0),
         (150, 190, 300.0), (220, 260, 300.0), (300, 340, 300.0)],
        [(10, 13), (15, 18), (20, 23), (25, 28), (30, 33), (35, 38), (40, 43),
         (45, 48), (50, 53), (55, 58), (60, 63), (65, 68), (70, 73), (75, 78),
         (150, 190), (220, 260), (300, 340)],
        MIN_GAP_MERGE,
        MIN_LINE_HEIGHT,
        "Asserting that a real pair merges is not enough, and this is the case that "
        "proves it. Fourteen 3-row specks, each two empty rows from the next, on a "
        "page whose real lines are 40 rows. Every speck pair satisfies "
        "2*min(3,3) <= 40, so only `max(ha,hb) >= min_line` refuses them; drop it "
        "and the chain fuses into a single 68-row band, inside the 80-row ceiling "
        "and comfortably past the height filter, and the recogniser is asked to "
        "read noise. Two pieces both too short to be a line do not become one by "
        "being adjacent.",
    ),
    (
        "a speckle chain on a page speckle does not outnumber",
        700,
        [(10, 13, 20.0), (15, 18, 20.0), (20, 23, 20.0), (25, 28, 20.0),
         (30, 33, 20.0),
         (100, 140, 300.0), (170, 210, 300.0), (240, 280, 300.0),
         (310, 350, 300.0), (380, 420, 300.0), (450, 490, 300.0),
         (520, 560, 300.0), (590, 630, 300.0)],
        [(10, 13), (15, 18), (20, 23), (25, 28), (30, 33),
         (100, 140), (170, 210), (240, 280), (310, 350), (380, 420),
         (450, 490), (520, 560), (590, 630)],
        MIN_GAP_MERGE,
        MIN_LINE_HEIGHT,
        "The case above is not enough, and a sibling port's review is what showed "
        "it. There the twelve or fourteen specks OUTNUMBER the real lines, so "
        "dropping the median filter collapses `typical` to 3 and the chain cannot "
        "grow past a ceiling of 6 — decisions 1 and 4 mask each other, and "
        "reverting BOTH together was caught by only one case. Here five specks sit "
        "among eight 40-row lines, so the median is 40 whether it is filtered or "
        "not, and `max(ha, hb) >= min_line` is the ONLY thing refusing the chain. "
        "Drop it and five 3-row specks fuse into one 23-row band that clears the "
        "height filter. Five ports were in exactly the both-reverted state on "
        "2026-08-29, so this is the state the fixture most needs to catch.",
    ),
    (
        "merging must not cascade down a page",
        700,
        [(20, 70, 300.0), (70, 72, 5.0), (72, 122, 300.0), (122, 124, 5.0),
         (124, 174, 300.0), (174, 176, 5.0), (176, 276, 300.0), (276, 278, 5.0),
         (278, 328, 300.0), (328, 330, 5.0), (330, 380, 300.0), (380, 382, 5.0),
         (382, 432, 300.0), (432, 434, 5.0), (434, 484, 300.0), (484, 486, 5.0)],
        [(20, 70), (72, 122), (124, 174), (176, 276), (278, 328), (330, 380),
         (382, 432), (434, 484)],
        MIN_GAP_MERGE,
        MIN_LINE_HEIGHT,
        "Eight runs, each two inked rows from the next, so gap_has_ink is true "
        "everywhere and only the ceiling can refuse anything. Seven runs are 50 "
        "rows and ONE is 100, which is the whole point: the median is 50 and the "
        "MAX is 100, so reading typical off the max doubles the ceiling to 200, the "
        "chain starts collapsing and 8 bands become 3. A page of equal-height runs "
        "cannot see that at all, because there the median and the max are the same "
        "number.",
    ),
    (
        "no run clears min_line, so the unfiltered median stands",
        200,
        [(10, 13, 20.0), (13, 14, 5.0), (14, 17, 20.0), (60, 65, 20.0),
         (100, 105, 20.0), (140, 145, 20.0)],
        [(10, 13), (14, 17), (60, 65), (100, 105), (140, 145)],
        MIN_GAP_MERGE,
        MIN_LINE_HEIGHT,
        "A page of nothing but sub-minimum runs, where the height filter would "
        "leave no heights to take a median of. Falling back to the UNFILTERED "
        "median is safe rather than principled — the height filter discards this "
        "whole page anyway, so no crop depends on the value — but the fallback has "
        "to be the median and not a constant. Heights 3/3/5/5/5 give a median of 5 "
        "and a ceiling of 10, which admits the 7-row merge of the inked-dip pair; "
        "a fallback of 1 gives a ceiling of 2 and refuses it.",
    ),
    (
        "degenerate zero-height runs, typical floored at one",
        40,
        [(10, 12, 5.0), (12, 15, 20.0)],
        [(10, 10), (11, 11), (12, 15)],
        MIN_GAP_MERGE,
        MIN_LINE_HEIGHT,
        "The run collector cannot emit an empty run, but `merge_runs` is a free "
        "function and a caller can hand it one — the reference says as much about "
        "touching runs. Heights 0/0/3 clear nothing, the unfiltered median is 0, "
        "and without the floor of 1 the ceiling is 0 and refuses every merge "
        "including the zero-width one. This case exists to pin that floor, not one "
        "of the four decisions.",
    ),
    (
        "a single run is returned unchanged",
        40,
        [(5, 25, 300.0)],
        [(5, 25)],
        MIN_GAP_MERGE,
        MIN_LINE_HEIGHT,
        "Nothing to merge into, so no decision can change the answer — this case "
        "pins the fold's SEEDING instead. A fold written as 'join the last run, "
        "otherwise start a new one' that forgets the empty case drops the first run "
        "silently, and one run is the minimal input that shows it. Larger cases "
        "catch the same bug; this is the one where nothing else can be blamed.",
    ),
    (
        "no runs at all",
        40,
        [],
        [],
        MIN_GAP_MERGE,
        MIN_LINE_HEIGHT,
        "The early return. Without it the median of an empty list is an index out "
        "of bounds, which is a crash rather than a wrong answer, so this is the "
        "only case that separates the two.",
    ),
]


def build_profile(length, fills):
    """The row profile a port must build from the same case description.

    Fills are applied IN ORDER and overwrite, which is how a one-row sub-threshold
    dip is written over the band it sits inside.
    """
    hist = [0.0] * length
    for a, b, value in fills:
        for y in range(a, b):
            hist[y] = float(value)
    return hist


def evaluate():
    """Every case's expectation, plus the mutation matrix, plus the oracle's verdict."""
    rows = []
    for name, length, fills, runs, max_gap, min_line, note in CASES:
        hist = build_profile(length, fills)
        runs = [tuple(r) for r in runs]

        # The contract is ASCENDING, NON-OVERLAPPING runs, which is what the run
        # collector emits. It is enforced here rather than assumed because it is the
        # one input shape on which this Python and the canonical Rust could disagree:
        # Rust computes `r1 - last.0` in `u32`, so an overlapping pair underflows and
        # panics in a debug build, where Python would quietly produce a negative span
        # that passes the ceiling. A fixture case outside the contract would be
        # pinning behaviour the ports do not share.
        previous_end = None
        for r0, r1 in runs:
            if r0 > r1 or r1 > length:
                sys.exit(f"{name}: run ({r0}, {r1}) does not fit a {length}-row profile")
            if previous_end is not None and r0 < previous_end:
                sys.exit(
                    f"{name}: run ({r0}, {r1}) overlaps the previous run, which is "
                    "outside the contract — see the comment above"
                )
            previous_end = r1

        expected = merge_runs(runs, hist, max_gap, min_line)

        oracle = brute_force_partition(runs, hist, max_gap, min_line)
        if oracle != expected:
            sys.exit(
                f"{name}: the fold and the enumerated characterisation disagree — "
                f"fold {expected}, oracle {oracle}"
            )

        killed = []
        for mutation, (variant, _decision) in MUTATIONS.items():
            try:
                got = merge_runs(runs, hist, max_gap, min_line, variant)
            except (IndexError, ValueError):
                # A variant that crashes on this input is caught by it just as
                # surely as one that returns the wrong answer.
                killed.append(mutation)
                continue
            if got != expected:
                killed.append(mutation)

        combinations_killed = []
        for label, (variant, _minimum) in COMBINATIONS.items():
            try:
                got = merge_runs(runs, hist, max_gap, min_line, variant)
            except (IndexError, ValueError):
                combinations_killed.append(label)
                continue
            if got != expected:
                combinations_killed.append(label)

        rows.append(
            {
                "name": name,
                "note": note,
                "profile_length": length,
                "profile_fills": [[a, b, float(v)] for a, b, v in fills],
                "runs": [[a, b] for a, b in runs],
                "max_gap": max_gap,
                "min_line": min_line,
                "typical": typical_height(runs, min_line, REFERENCE) if runs else 0,
                "expected": [[a, b] for a, b in expected],
                "discriminates": killed,
                "discriminates_combined": combinations_killed,
            }
        )
    return rows


# The battery above varies ONE decision at a time, and that is not sufficient on its
# own: a sibling port's review showed that reverting decisions 1 and 4 TOGETHER was
# caught by a single case, because the unfiltered median collapses `typical` far
# enough that the loosened fragment clause happens to reproduce the right answer.
# Deviations that mask each other are the ones a per-decision battery cannot see.
#
# This is not a hypothetical combination. It is the exact state the web, Android, iOS,
# Go and `monocr` Python implementations were in on 2026-08-29: median over every run,
# and `2 * min(ha, hb) <= typical` with no `min_line` guard. Two killers are required
# rather than one so that deleting a single case cannot un-gate it.
COMBINATIONS = {
    "the state five ports were actually in": (
        replace(
            REFERENCE,
            filter_median_by_min_line=False,
            fragment_min_line_guard=False,
        ),
        2,
    ),
}


def audit(rows, verbose):
    """Fail if the case set does not actually pin the algorithm.

    Three ways it can fail to: a mutation that survives every case, a case that no
    mutation kills, or a combination in `COMBINATIONS` with fewer killers than it
    requires. The third is the one a per-decision battery cannot see for itself.

    Note that `padding` is judged on single-decision kills only. A case that catches
    a combination but no single deviation is still padding — the combination is
    already covered by the cases that do both.
    """
    survivors = sorted(
        set(MUTATIONS) - {m for row in rows for m in row["discriminates"]}
    )
    padding = [row["name"] for row in rows if not row["discriminates"]]

    if verbose:
        width = max(len(m) for m in MUTATIONS)
        print("mutation battery — which cases kill which deviation")
        for mutation, (_variant, decision) in MUTATIONS.items():
            killers = [r["name"] for r in rows if mutation in r["discriminates"]]
            print(f"  decision {decision}  {mutation:<{width}}  {len(killers)} killer(s)")
            for killer in killers:
                print(f"      {killer}")

    weak = []
    for label, (_variant, minimum) in COMBINATIONS.items():
        killers = [r["name"] for r in rows if label in r["discriminates_combined"]]
        if verbose:
            print(f"  combined  {label}  {len(killers)} killer(s), {minimum} required")
            for killer in killers:
                print(f"      {killer}")
        if len(killers) < minimum:
            weak.append(f"{label}: {len(killers)} killer(s), {minimum} required")

    if survivors:
        sys.exit(
            f"{len(survivors)} mutation(s) survive every case, so the fixture does "
            f"not pin the decision they alter: {survivors}"
        )
    if padding:
        sys.exit(
            f"{len(padding)} case(s) are killed by no mutation, so they are padding: "
            f"{padding}"
        )
    if weak:
        sys.exit(
            "combined deviations are under-covered, and combinations are where "
            f"single-decision mutations mask each other: {weak}"
        )
    for decision in (1, 2, 3, 4):
        covered = [m for m, (_v, d) in MUTATIONS.items() if d == decision]
        if not covered:
            sys.exit(f"decision {decision} has no mutation attacking it")
    print(
        f"mutation battery: {len(MUTATIONS)} deviations, all killed; "
        f"{len(COMBINATIONS)} combination(s), all killed by at least the required "
        f"number of cases; {len(rows)} cases, none padding"
    )


def generate(rows):
    return {
        "_comment": (
            "Generated by shared/segmentation-fixtures/generate-merge-cases.py from "
            "the SPECIFICATION of merge_runs, not from any of its ten "
            "implementations — a fixture whose oracle is one of the ports proves "
            "only that the ports agree with each other. Do not hand-edit expected "
            "values; regenerate, and verify with --check. Consumed by the web, "
            "Android, iOS and Rust line-merge tests. "
            "WHAT THIS PINS: (1) typical is the MEDIAN of run heights filtered to "
            "runs at least min_line tall, falling back to the unfiltered median "
            "when none qualify, floored at 1; (2) ceiling is typical * 2 and no "
            "merge may produce a taller band; (3) a merge needs gap_size <= "
            "max_gap AND (every gap row inked OR fragment) AND a result inside the "
            "ceiling; (4) fragment is 2 * min(ha, hb) <= typical AND max(ha, hb) "
            ">= min_line, so a fragment attaches to a line and never to another "
            "fragment. "
            "WHY IT IS TRUSTWORTHY: generation fails unless all 20 single-decision "
            "mutations in the generator's battery are killed by some case and every "
            "case kills at least one — a case that passes under every variant is "
            "padding. Because single-decision deviations can MASK each other, it "
            "also fails unless the one combined deviation that five ports were "
            "actually in on 2026-08-29 (unfiltered median plus a fragment clause "
            "with no min_line guard) is caught by at least two independent cases; "
            "'discriminates_combined' records which. It also fails unless the greedy "
            "fold agrees with an independent brute-force enumeration of every way to "
            "cut the run list into consecutive groups. Both lists are written by "
            "those checks, not by hand, so they cannot go stale. "
            "READ 'note' BEFORE CHANGING ANY CASE: most carry ordinary "
            "full-height companion lines because a two-run page is degenerate — "
            "with two runs the median IS one of the two, so a case meant to isolate "
            "one clause has another firing alongside it. Three fixtures in "
            "monocr-onnx/rust/src/segmenter.rs had that defect."
        ),
        "min_gap_merge": MIN_GAP_MERGE,
        "min_line_height": MIN_LINE_HEIGHT,
        "mutations": {
            name: f"decision {decision}" for name, (_v, decision) in MUTATIONS.items()
        },
        "cases": rows,
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
        "--audit",
        action="store_true",
        help="print the mutation matrix (it runs either way)",
    )
    parser.add_argument(
        "--cross-check",
        action="store_true",
        help="print the brute-force oracle's work (it runs either way)",
    )
    args = parser.parse_args()

    rows = evaluate()
    if args.cross_check:
        for row in rows:
            print(
                f"  oracle agrees: {row['name']} "
                f"({len(row['runs'])} runs -> {len(row['expected'])} bands)"
            )
        print(f"cross-check: the fold and the enumeration agree on {len(rows)} cases")
    audit(rows, args.audit)

    rendered = json.dumps(generate(rows), indent=2) + "\n"
    if args.check:
        if not args.output.is_file():
            sys.exit(f"{args.output} does not exist, so there is nothing to check")
        if args.output.read_text(encoding="utf-8") != rendered:
            sys.exit(
                f"{args.output} does not match what the specification produces now. "
                "Either a port edited the fixture by hand or a case moved; both need "
                "a human."
            )
        print(f"{args.output} matches the specification")
        return
    args.output.write_text(rendered, encoding="utf-8")
    print(f"wrote {args.output} ({len(rows)} cases)")


if __name__ == "__main__":
    main()
