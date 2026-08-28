# Line segmentation: four surfaces, and three real divergences

**Status:** recorded, not resolved. Measured 2026-08-15, corrected 2026-08-27.

Web, Android and iOS each carry their own port of the same horizontal
projection-profile line segmenter, and the CLI delegates to a fourth in
`monocr-onnx/rust`. This document said "three ports" and left the CLI out; it was
written before `apps/cli` had a mode enum. They are meant to be the same algorithm, and
on two parameters they are not, so **the same page can produce different line
sets on different platforms**. That is a correctness property of the product:
a user photographing one page on two devices can get two different transcripts,
and nothing in the repository currently notices it.

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
| Polarity normalised before segmenting | yes                     | yes                | yes                | **no**                      |
| Printed-rule suppression              | yes                     | yes                | yes                | **no**                      |

Two of the first three are Android against the others; the third is web against
the others. So no surface is the reference, and no two agree completely.

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
delegates to, and it is blocked on a linker rather than a decision.

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

**Which one is right.** Nothing has measured these three against a page with a
known line count, so picking a winner would be taste presented as a result. The
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

**Three of the four have it: web, Android and iOS.** Ported 2026-08-28. Each port
was measured through its own parameter set rather than inheriting the reference
figure — bands over the same twelve pages, without → with:

| surface                      | bands without |         with |
| ---------------------------- | ------------: | -----------: |
| **web**                      |            68 |      **160** |
| iOS                          |            68 |     _ported_ |
| Android                      |            70 |     _ported_ |
| CLI (via `monocr-onnx/rust`) |           118 | _not ported_ |

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
two ports here were written against suites that run: Android 32 tests through the
restored wrapper, iOS 45 through `Scripts/swift-test.sh`. The Rust one is untouched
because it lives in `monocr-onnx`, not because it is still blocked.

An earlier version of this section said _no_ surface had it, which was true for
about an hour.

### How the three are held together

`shared/segmentation-fixtures/rule-cases.json`, generated by `generate-rule-cases.py`
and asserted by all three ports. Fourteen cases, each a mask plus the ink count and a
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
step, and why the ports follow the sentence instead. The consequence of the second
is that one pixel of every rule survives suppression on any page of even width, which
is all of them at `max(15, int(w * 0.5))`. Raised for `mon_OCR` to resolve.

`--cross-check` re-derives that classification against live cv2 and **fails on any
divergence it cannot attribute to one of those two**, so a third cannot hide behind
the known pair.

That makes step 3.5 different in kind from everything above. The rows in the first
table are unresolved because picking a winner needs ground truth nobody has; this one
was unresolved only because it had not been ported, and now it is, everywhere the
port could be verified. `mon_OCR`'s ROADMAP 4.5.7 tracks the remainder.

## What would close it

A page set with counted lines, run through all four surfaces. That is the same
missing artifact as `mon_OCR`'s `DATA_STRATEGY.md` rung D2, real page images
with ground truth, and it closes several open questions at once. Until it
exists, aligning them would be choosing one arbitrary setting over three others
and calling it parity.

That argument does **not** cover step 3.5 above, which needs no ground truth to
justify: a page returning zero characters is measurable without a baseline. Three of
the four surfaces now carry it, and the fourth is a different repository.
