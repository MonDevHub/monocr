# Line segmentation: four surfaces, and where they disagree

**Status:** partly resolved. Measured 2026-08-15, corrected 2026-08-27 and 2026-08-28.

**The heading used to carry a count, and it was wrong three times.** It said six; on
the day it was removed the table held five rows on which web, Android and iOS do not
all agree, and three rows had closed rather than the two the note claimed. Each
closure is good news that silently invalidated the tally, so the number was most
wrong exactly when the file was most useful. The lesson was already written here —
"its own small lesson about keeping a tally in a heading" — and kept underneath a
heading that still carried one. The count now lives only in the table, where it is
recomputed by reading it.

Web, Android and iOS each carry their own port of the same horizontal
projection-profile line segmenter, and the CLI delegates to a fourth in
`monocr-onnx/rust`. This document said "three ports" and left the CLI out; it was
written before `apps/cli` had a mode enum. They are meant to be the same algorithm, and
on several parameters they are not, so **the same page can produce different line
sets on different platforms**. That is a correctness property of the product:
a user photographing one page on two devices can get two different transcripts.

Until 2026-08-28 nothing in the repository noticed any of it. Two rows are now held
by generated fixtures and cannot drift again without a test going red. Every other
row still would.

## Where the code is

| Platform | File                                                                                                                                                                  |
| -------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Web      | [`apps/web/src/lib/segmentation.ts`](../../../apps/web/src/lib/segmentation.ts)                                                                                       |
| Android  | [`apps/android/app/src/main/java/dev/janakhpon/monocr/engine/LineSegmenter.kt`](../../../apps/android/app/src/main/java/dev/janakhpon/monocr/engine/LineSegmenter.kt) |
| iOS      | [`apps/ios/monocr-ios/LineSegmenter.swift`](../../../apps/ios/monocr-ios/LineSegmenter.swift)                                                                         |
| CLI      | delegates to `monocr-onnx/rust/src/segmenter.rs`; sets its ratio in [`apps/cli/src/mode.rs`](../../../apps/cli/src/mode.rs)                                           |

## What agrees

Across **web, Android and iOS**: adaptive threshold window 25 and constant 8;
horizontal smear kernel 11; vertical smear kernel 5; density ratio 0.03; minimum
line height 10; padding 25% vertical and 20% horizontal; the same three outlier
rejections; and, since 2026-08-28, printed-rule suppression at span 0.5 with the
0.8 ink-share ceiling — the only one of these held by a shared generated fixture
rather than by three files happening to agree.

**The CLI shares almost none of it**, because it is a different implementation
rather than a port of these: a flat global threshold at 128 instead of an adaptive
one, no morphological smear at all, a fixed 4px pad on both axes, and smoothing 3.

It does share one row, and it is the newest: **all four surfaces now calibrate the
gap threshold on the smoothed row profile and detect boundaries on the raw one.**
The apps moved on 2026-08-28 and the CLI's implementation, `monocr-onnx` commit
`8918ae9`, the same day. That row used to sit in the table below and is the only
divergence this file has ever closed on every surface at once.

## What does not

|                                       | Web                     | Android            | iOS                | CLI                         |
| ------------------------------------- | ----------------------- | ------------------ | ------------------ | --------------------------- |
| Histogram smoothing kernel            | 3                       | **5**              | 3                  | 3                           |
| Grayscale blur                        | 3×3                     | **5×5**            | 3×3                | **none**                    |
| Blur edge handling                    | shrink window           | **replicate edge** | shrink window      | n/a                         |
| Binarizes against                     | **unblurred** grayscale | blurred            | blurred            | **global 128**              |
| Density ratio, page mode              | 0.03                    | 0.03               | 0.03               | **0.05**                    |
| Mode selection                        | **none**                | provenance + shape | provenance + shape | `--mode`, `auto`, `inspect` |
| Polarity normalised before segmenting | yes                     | yes                | yes                | yes                         |
| Printed-rule suppression              | yes                     | yes                | yes                | yes                         |

The first three rows are all Android against the others; the fourth is web against
the others. So no surface is the reference, and no two agree completely.

That sentence read "two of the first three ... the third is web" until the blur
edge-handling row was inserted above the binarisation row on 2026-08-28, which is a
neat demonstration of why a positional reference into a table ages badly.

The printed-rule row inverted on 2026-08-28: it read web-against-three, and now reads
CLI-against-three. See the fifth-divergence section for what that port pinned.

**The blur edge-handling row is new, and was missed for the same reason the density
ratio was.** Both were read as one number, "3×3 against 5×5", when the kernel size
and what happens at the image border are separate decisions. Android clamps the
sample index and always divides by the full kernel, replicating the edge pixel; web
and iOS skip out-of-range samples and divide by how many they actually took. The two
disagree in the outermost rows and columns _even at an identical kernel size_, so
aligning Android's 5 to 3 would not close it. Low-impact, since the affected band is two pixels wide. It is still a divergence,
and this file exists to list them.

**The 0.03-against-0.05 row is a correction.** This document previously listed the
density ratio under "what agrees", which was true of the three apps and never of
the CLI. `apps/cli/src/mode.rs:39` calls 0.05 "the library default" and pins it by
test, so the two values are each deliberate and simply were never compared.

**The CLI is the only surface that does not normalise polarity**, and that row was
missing from this document until 2026-08-27 — a three-against-one divergence absent
from the file whose sole purpose is recording them. It matters because the segmenter
treats dark as ink: handed a light-on-dark page, the CLI segments the _background_
and returns the gaps between lines. The gap is in `monocr-onnx/rust`, which the CLI
delegates to, so it is unported rather than undecided: it lives in another
repository. This paragraph used to say it was blocked on a linker. That blocker was
never real, and the fifth-divergence section below retracts it in full.

**Web has no mode at all**, which is worth stating beside the rest: it is the one
surface where a fused-block reading cannot be retried, because there is no other
parameter set to retry with. Its `lineShaped` flag also stops at
`console.warn`, because the worker posts a bare string, so a user sees fused text
as ordinary output.

**Why it matters more than the numbers suggest.** The smoothing kernel is
applied to the row-ink profile before bands are detected, so it decides directly
whether two closely-set lines merge into one band or stay apart. A larger kernel
merges more. Android merges more readily than web and iOS, on the same page.

## What is not claimed here

**Which one is right, for the rows above.** Nothing has measured those three
against a page with a known line count, so picking a winner would be taste
presented as a result.

### The one that turned out not to be taste

That reasoning does **not** cover everything this file used to list under it, and
one row left the table on 2026-08-28 because of it. All four surfaces detected run
boundaries on the smoothed row profile where the reference uses the raw one. That
was never a question of taste: the reference calibrates its threshold on the
smoothed profile because a mean is more stable there, and detects on the raw one
because smoothing bleeds ink across a narrow gap, and it says so in the code. It
was measurable without ground truth, and it was measured.

Each surface was measured through its own parameter set, and the results are not
transferable — which is the point worth keeping.

**The three apps**, on pages of 14px lines: at gaps of 5, 6 and 8 px the smoothed
profile returned **one band** against 29, 28 and 25 lines drawn. The drawn count
falls as the gap grows because the page height is fixed. On the raw profile each
page returns exactly its drawn count, and from 12 px up the two profiles agree.

**The CLI**, on 29 drawn bands at its own defaults (minimum line height 10,
smoothing 3, ratio 0.05): the smoothed profile returned **one band** at gaps of 1
and 2 px, and 29 from 3 px up.

**So the CLI's window of harm is two pixels wide where the apps' spans at least
eight, and the smoothing kernel is not why.** Web and the CLI both smooth at 3.
The apps dilate the mask vertically before taking the profile and the Rust
implementation has no smear at all, so the apps carry ink across a gap further
than smoothing alone accounts for. Gaps of 9 to 11 px were not measured on the
apps, so "at least eight" is the honest bound.

The break point is the smoother's **effective** width, `2·(window/2)+1`, not the
window as written — integer division means an even window behaves as the odd one
above it. Measured on the CLI at windows 2 through 16: a window of 4 still fuses a
gap of exactly 4, and recovers at 5. Every test value in that suite is odd, so
nothing there would have caught the even case; it is recorded rather than relied
on.

Detecting on the raw profile also **narrows every band** by the smoother's
half-width at each end — a fixed 2px at window 3, on every line, at every gap. No
shared fixture pins a line bbox or height, and the one downstream consumer of the
height, `looks_like_a_line` in the CLI, flags rather than filters and stores its
verdict as an advisory field. So the narrowing is unobserved, and where it does
change that flag it moves it toward the band's true extent.

**The risk this created is no longer a risk. It was measured on 2026-08-28 and it
is worse than the fusing this row fixed.** Full finding: `mon_OCR`
`docs/AUDIT-2026-08-B.md` F-69.

Mon stacks diacritics above and below the base line, and at print resolution the
gap between a diacritic row and its base clears zero in the **raw** profile — so
raw detection splits one text line into two. Measured through the CLI at its
default 300 DPI over a 145-page Mon book scan: **45 of 145 pages have more than
40% of their bands decoding to majority-digit nonsense.** On one clean page of
about 22 text lines:

| render DPI | bands, detect on raw | bands, detect on smoothed |
| ---------- | -------------------: | ------------------------: |
| 150        |                   20 |                        21 |
| 200        |               **29** |                        21 |
| 300        |               **40** |                        24 |

The median band height halves, 94 px to 35 px, and the fragments overlap once
padded — the CLI's own manifest shows gaps of −5 to −7 px between consecutive
bands, each crop carrying its neighbour's ink. **The effect is absent at 150 DPI**,
which is why no fixture in this repository caught it: they all draw one band height
at one scale.

**The reference does not have this defect because it pairs raw detection with a
bounded merge** — `MIN_GAP_MERGE`, fuse when the gap is ≤ 10 px and the raw
histogram is non-zero throughout. The gaps here are 1–3 px. When this row was
written no port had a merge step at all, so it closed half of a pair. All ten
implementations now carry the merge — see the seventh divergence below, which is
about the three that carried only half of it.

**Interim mitigation, free today:** `--dpi 150` on the CLI. The apps have no
equivalent knob, because they segment whatever the camera hands them.

The lesson for the rest of the table is to check which kind of divergence each row
is before filing it here. A value with no ground truth is one thing; a documented
reason the ports had not read is another. The
upstream trainer, `mon_OCR`, uses different values again: smoothing 15,
adaptive window 31, constant 15, density ratio 0.12, tuned against rendered
book pages. Its own documentation records that the ratio suits books at 0.12
and posters at 0.50. There is no single correct setting across document types,
which is part of why this is recorded rather than unified.

## A fifth divergence, newer and larger than any above

`mon_OCR`'s segmenter gained printed-rule suppression on 2026-08-27 (step 3.5).
Measured on twelve real MNEC papers, nine collapsed to a single band without it and
seven of those returned 0–2 characters. Pages carrying no rules come back
byte-identical, so it is not a trade-off between document types the way the density
ratio is.

**All four have it.** Web already did; Android and iOS were ported on 2026-08-28,
and `monocr-onnx/rust` the same day, which the CLI inherits. Rust was the last of
**ten** implementations across five repositories without it — the others being
`mon_OCR`, `monocr`, `mon-corpus-scraper` and the Python, JS and Go bindings, all
of which had gained it independently. This file said "only web has it" for long
enough to be wrong in both directions. Each port
was measured through its own parameter set rather than inheriting the reference
figure — bands over the same twelve pages, without → with:

| surface                      | bands without |     with |
| ---------------------------- | ------------: | -------: |
| **web**                      |            68 |  **160** |
| iOS                          |            68 | _ported_ |
| Android                      |            70 | _ported_ |
| CLI (via `monocr-onnx/rust`) |           118 | _ported_ |

The iOS and Android columns say _ported_ rather than a band count because the
twelve-page measurement needs the page images, which live with the upstream
measurement and not in this repository. What is pinned instead is stronger for the
purpose of this document: all three ports assert against one generated fixture, so
they cannot drift apart again without a test going red.

**This section previously said the three unported surfaces were blocked by tooling
on the machine the work was done on**: a missing `clang_rt.osx` for Rust, Xcode
absent for iOS, and Gradle unable to resolve a JDK offline for Android. Every one of
those was a mis-configured lookup rather than a missing toolchain, and all three are
resolved. `docs/guides/mobile-build-and-test.md` records what each actually was. The
two ports here were written against suites that run: Android 50 tests through the
restored wrapper, iOS 48 through `Scripts/swift-test.sh`. The Rust one is untouched
because it lives in `monocr-onnx`, not because it is still blocked.

An earlier version of this section said _no_ surface had it, which was true for
about an hour.

### How the three are held together

`shared/segmentation-fixtures/rule-cases.json`, generated by `generate-rule-cases.py`
and asserted by all three ports. Twenty-three cases, each a mask plus the ink count and a
position-weighted checksum after suppression. Position-weighted because a bare count
would not notice a pass that removed the right _number_ of pixels in the wrong places,
which is the failure an off-by-one in a run-length scan actually produces.

**Generated from the specification, not from the reference implementation, because
the two disagree.** `mon_OCR`'s `_suppress_page_rules` is a pair of cv2 morphological
openings implementing the sentence "a rule is an unbroken ink run spanning at least
`_RULE_SPAN` of the page in one direction". It deviates from that sentence twice, and
both were found by generating this fixture rather than by reading the code:

|               | what the reference does                                                                                | why                                                                                                                                                       |
| ------------- | ------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Border**    | a 10px run flush against an image edge counts as a rule at span 15; the same run one pixel in does not | `cv2.erode` defaults its border value to +inf, so for a uint8 mask the pixels outside the image read as ink and an edge-flush run is effectively infinite |
| **Even span** | a 14px run at x=2..15 is marked at x=3..16                                                             | `getStructuringElement` anchors at `ksize / 2`, which is not the centre of an even-width kernel                                                           |

Both make the reference remove the wrong _pixels_ rather than the wrong _number_ of
them, which is why neither showed up in the band-count measurement that justified the
step, and why the ports follow the sentence instead. Raised for `mon_OCR` to resolve.

**An earlier version of this paragraph overstated the second one**, and the correction
is worth keeping because the overstatement was the plausible reading rather than a
slip. It said one pixel of every rule survives on any page of even width, "which is
all of them". Neither half holds. The span is `max(15, int(w * 0.5))`, which is even
for roughly half of page widths, not all: 100 and 101 both give 50, 102 and 103 both
give 51. And measured against cv2 on interior runs of every length from the span up
to the page width, the loss is exactly **one pixel at the leading end of the run**,
never a pixel of its body. A rule that runs to the image edge loses nothing at all,
because the border behaviour above covers for the shift. A page border, which is the
case step 3.5 exists for, is therefore removed completely.

`--cross-check` re-derives that classification against live cv2 and **fails on any
divergence it cannot attribute to one of those two**, so a third cannot hide behind
the known pair.

That makes step 3.5 different in kind from everything above. The rows in the first
table are unresolved because picking a winner needs ground truth nobody has; this one
was unresolved only because it had not been ported, and now it is, everywhere the
port could be verified. `mon_OCR`'s ROADMAP 4.5.7 tracks the remainder.

## A sixth, in the same pipeline and absent from this file until it was fixed

`PageNormalizer` runs before the segmenter on Android and iOS, levelling the page
background so the adaptive threshold sees flat paper. It estimates that background by
dilating a downsampled copy, and **the two ports used different structuring
elements**: Android a disk, matching the `cv2.MORPH_ELLIPSE` the reference asks for,
iOS the bounding square. A square contains the inscribed disk, so iOS propagated more
background over the ink, its estimate came back brighter, and dividing by it made
every iOS page darker. Measured over eight synthetic pages, seven disagreed.

Fixed on 2026-08-28 by porting Android's implementation to iOS. Sixty-three of
seventy-two pages are now byte-identical between the two; the remaining nine differ
by at most 0.0044% of total luminance, from ±1 rounding in the area downsample where
**both** ports differ from cv2 on three or four pixels in six hundred. That residue
is recorded rather than closed: matching cv2 exactly would mean reimplementing its
fixed-point resize in two languages.

Two things about this belong in a document whose stated purpose is listing
divergences. It was never in it — the file covered the segmenter and treated the
step feeding it as out of scope, which is the same omission it criticises itself for
at the polarity row. And both ports had passing tests throughout: iOS compared its
dilation against a naive oracle that was also a square, so an implementation and its
oracle agreed about a shape neither had checked, while Android's `PageNormalizer`
had no tests at all because every internal was private. The shape is now pinned on
both sides against `shared/segmentation-fixtures/dilate-cases.json`, generated from
cv2.

## A seventh: half the ten carried two of the merge's four decisions

`merge_runs` is the missing half the fifth divergence above asked for, and by
2026-08-29 all ten implementations had it. Ten copies of one algorithm is not the
same as ten agreeing copies, and they did not agree.

The algorithm makes four decisions. Two of them are about the page's own typical
line height, and **web, Android, iOS, `monocr-onnx`'s Go port and `monocr`'s
Python implemented the other two only**:

| decision | canonical (`monocr-onnx` Rust, JS, Python) | what the five did |
| --- | --- | --- |
| **1. `typical`** | median of run heights, **filtered to runs ≥ `min_line`**, falling back to the unfiltered median | median over every run, speckle included |
| **2. `ceiling`** | `typical * 2`, no merge may exceed it | same |
| **3. merge test** | `gap_size ≤ max_gap` AND (every gap row inked OR `fragment`) AND result fits the ceiling | same |
| **4. `fragment`** | `2 * min(ha, hb) ≤ typical` **AND `max(ha, hb) ≥ min_line`** | `2 * min(ha, hb) ≤ typical` alone |

Neither omission is cosmetic, and both were measured before the canonical version
grew them:

- **The unfiltered median lets noise decide what a typical line is.** The merge
  deliberately runs before the height filter, so its input still holds every speckle
  the profile picked up. On a sibling port 30% of collected runs were under the
  minimum, and on 8 of 55 pages that drove `typical` below 10 — one page reached
  `typical` 2 and a ceiling of 4, against a real line height of 35. The ceiling then
  refuses every merge, so **the pass switches itself off on exactly the pages that
  need it most**.
- **Without `max(ha, hb) ≥ min_line` a fragment can attach to another fragment.**
  Twelve 2-row specks then chain into one 46-row band, which clears the height filter
  and is handed to the recogniser as a line. Two pieces both too short to be a line do
  not become one by being adjacent.

The five all took `(runs, hist, maxGap)` and never received `min_line`, which each
one had on hand — every call site applies it as the height filter two lines later.
Fixed here on 2026-08-29 in web, Android and iOS by adding the parameter, the filter
and the guard; `monocr-onnx`'s Go port was fixed the same day in its own repository.

**Nine of the ten now agree.** The tenth is `monocr`'s Python, whose divergence is
declared in its `merge_runs` docstring and left as an owner decision rather than a
cleanup. That docstring's premise is now false, though: it justifies the divergence
on the grounds that "`monocr-onnx` and the reference … both take the median over the
unfiltered list", and three of four `monocr-onnx` bindings no longer do. The stanza
needs rewriting whichever way the divergence is resolved.

`mon_OCR`'s `scripts/segmenter_parity.py` extracts all 152 registry rows with no dead
basis pattern after these edits, and its `min_gap_merge` row now reports the
four-decision form for nine surfaces and the OR-form for `monocr` alone. That harness
is the cross-repository check; the fixture below is the behavioural one.

### How the ten are held together

`shared/segmentation-fixtures/merge-cases.json`, generated by
`generate-merge-cases.py` and asserted by web, Android, iOS and Rust. Pure
arithmetic — a list of runs, a row profile, `max_gap`, `min_line` and the expected
merged runs — so it costs nothing to consume in five languages and is immune to the
rendering differences that make the other fixtures expensive.

Five things about it are load-bearing, and each was a rebuild before it was a rule:

1. **The expectations come from the specification, not from a port.** The generator
   reimplements the four decisions from their statement. A fixture whose oracle is one
   of the implementations proves only that they agree with each other, and if two are
   wrong in the same way it certifies the bug — which is exactly the shape of the
   `PageNormalizer` defect one section up.
2. **Every case must discriminate.** Generation fails unless each of twenty-one
   single-decision deviations is caught by some case, and unless every case catches at
   least one. Each case's `discriminates` list is written by that battery rather than
   by hand, so it cannot go stale. A case that passes under every variant is padding,
   and padding in a parity fixture is green that means nothing.
3. **A two-run case is degenerate.** With two runs the median IS one of the two, so a
   case meant to isolate the ink clause has the fragment clause firing alongside it.
   Three fixtures in `monocr-onnx/rust/src/segmenter.rs` had that defect and were
   named for clauses they did not test. Every case here carries ordinary full-height
   companion lines, and the per-case `note` shows the arithmetic.
4. **Carrying companions is not being isolated by them.** This fixture shipped
   seventeen cases believing rule 3 was satisfied because the companions were
   present. They were, but its flagship fragment case had heights 19/42/42/42/42:
   the median the companions set was ALSO the tested run's own height, so
   `2*min(ha,hb) <= typical` and `2*min(ha,hb) <= max(ha,hb)` agree on it. Every
   other case coincided too — the pair sat at the median, or the fragment clause was
   false both ways, or the `min_line` guard refused regardless — and the
   neighbour-relative form, which the reference names as the cascade that took page
   47 from 36 bands to 10 and cost 92% of its characters, survived the whole file
   with nothing in `MUTATIONS` to notice. The test is not "are there companions" but
   "would the verdict change if the companions were removed". An eighteenth case
   answers yes for that clause; the two ceiling-boundary cases cannot, and their
   notes now say so in those words rather than implying otherwise.
5. **Deviations mask each other, so a per-decision battery is not enough.** Reverting
   decisions 1 and 4 together — the exact state five of the ten implementations were
   in — was caught by a single case, because the unfiltered median collapses `typical`
   far enough that the loosened fragment clause reproduces the right answer anyway.
   Found by a sibling port reviewing this fixture rather than by the battery, which
   cannot see it by construction. Generation now also runs that combination and
   requires two independent killers, so deleting one case cannot un-gate it.

The generator also cross-checks its greedy left-to-right fold against a brute-force
enumeration of every way to cut the run list into consecutive groups, keeping the
partitions where each internal join satisfies the merge test and each cut fails it.
Exactly one can, and it is the fold's answer. That shares no control flow with the
fold, which is the point: an off-by-one in the accumulator is invisible to a second
fold written the same way.

CI gates the file with `--check`, which re-derives every expectation, re-runs the
battery and re-runs the enumeration. It is the only one of the four fixtures whose
generator needs nothing but the standard library, so it is the only one CI can
regenerate rather than merely inspect.

## Open, found 2026-08-28, not acted on

Three more divergences from a sweep across the three apps and the reference. None is
fixed here, because each changes what every page reads on at least one platform and
that is the owner's call, not a sweep's.

A fourth was listed here and has since been closed on all four surfaces; it is
recorded above under *What agrees*, and what it cost to close is below under
*The one that turned out not to be taste*.

### Crop padding differs from the reference on both axes

|            | ports (all three)    | reference                     |
| ---------- | -------------------- | ----------------------------- |
| vertical   | `ceil(coreH · 0.25)` | `ceil(coreH · 0.40)`          |
| horizontal | `ceil(coreH · 0.20)` | `max(10, ceil(coreW · 0.05))` |

The three ports agree with each other and differ from the reference twice: they crop
tighter above and below, and they scale horizontal padding off the line's **height**
where the reference scales it off the line's **width** with a 10px floor. The 0.40
exists for stacked Mon diacritics above and below the core band, which is the script
this model reads.

### Vertical smear kernel: 5 in the ports, 3 in the reference

Horizontal agrees at 11 everywhere. The ports agree with each other.

### Capture quality exists in three different states

| surface | computed?                   | reaches the user?                     |
| ------- | --------------------------- | ------------------------------------- |
| web     | yes, sharpness and no-lines | no: `console.warn` inside the worker  |
| iOS     | yes, sharpness only         | no: `isSoft` has no caller in the app |
| Android | no                          | n/a                                   |

The one shared threshold matches exactly where both compute it. iOS's is dead code
reached only by its own tests, and Android has no equivalent at all: it folds the
zero-lines case into a whole-page fallback, so the case web reports as its
highest-priority warning is the one Android cannot report.

The fused-block warning is a separate flag and is in better shape than the above:
Android surfaces it in `HomeScreen` and iOS in `ResultCardView`. Only web stops at a
`console.warn` the user never sees. A first pass of this sweep recorded iOS as not
surfacing it either, which was wrong, and it is noted because the difference between
"computed" and "reaches a user" is exactly what this table is for.

## What would close it

A page set with counted lines, run through all four surfaces. That is the same
missing artifact as `mon_OCR`'s `DATA_STRATEGY.md` rung D2, real page images
with ground truth, and it closes several open questions at once. Until it
exists, aligning them would be choosing one arbitrary setting over three others
and calling it parity.

That argument does **not** cover step 3.5 above, which needs no ground truth to
justify: a page returning zero characters is measurable without a baseline. Three of
the four surfaces now carry it, and the fourth is a different repository.
