# Line segmentation: four surfaces, and six real divergences

**Status:** partly resolved. Measured 2026-08-15, corrected 2026-08-27 and 2026-08-28.

The count in that title has been wrong twice, which is its own small lesson about
keeping a tally in a heading. It is the number of rows in the table below on which
web, Android and iOS do not all agree, plus the two closed on 2026-08-28. Count the
rows before changing it.

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
| Boundaries detected on                | raw profile             | raw profile        | raw profile        | **smoothed profile**        |

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

That reasoning does **not** cover everything this file used to list under it, and
one row left the table on 2026-08-28 because of it. All three ports detected run
boundaries on the smoothed row profile where the reference uses the raw one. That
was never a question of taste: the reference calibrates its threshold on the
smoothed profile because a mean is more stable there, and detects on the raw one
because smoothing bleeds ink across a narrow gap, and it says so in the code. It
was measurable without ground truth, and it was measured: pages of 14px lines
separated by 5, 6 and 8 pixels each came back as ONE band, against 29, 28 and 25
lines drawn. On the raw profile they come back as exactly the drawn count, and at
12px and wider the two agree exactly.

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

## Open, found 2026-08-28, not acted on

Four more divergences from a sweep across the three apps and the reference. None is
fixed here, because each changes what every page reads on at least one platform and
that is the owner's call, not a sweep's.

### The ports detect line boundaries on the smoothed histogram; the reference does not

The highest-impact one. `mon_OCR/src/monocr/segmenter.py` calibrates its threshold
from the smoothed row-ink profile and then detects runs on the **raw** one, and says
why in the code:

> the smoothed hist bleeds across true inter-line gaps when lines are tightly packed,
> so using it for boundary detection would merge distinct lines. The raw hist has zero
> rows between any lines that have a true ink gap after dilation.

All three ports test `hist[y] > threshold` against the smoothed profile: web
`segmentation.ts`, Android `LineSegmenter.kt`, iOS `LineSegmenter.swift`. They
therefore all carry the line-merging behaviour that comment describes, and it is
the failure `looksLikeALine` exists to flag after the fact.

This one is different in kind from the density ratio. Which ratio suits a book page
is a measurement question with no single answer; this is the reference stating that
one of the two profiles is the wrong input for this decision and giving the reason.
Changing it would change line splitting on every platform at once, which is why it is
recorded rather than done.

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
