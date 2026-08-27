# Line segmentation: four surfaces, and three real divergences

**Status:** recorded, not resolved. Measured 2026-08-15, corrected 2026-08-27.

Web, Android and iOS each carry their own port of the same horizontal
projection-profile line segmenter, and the CLI delegates to a fourth in
`monocr-onnx/rust`. This document said "three ports" and left the CLI out; it was
written before `apps/cli` had a mode enum. They are meant to be the same algorithm, and
on two parameters they are not, so **the same page can produce different line
sets on different platforms**. That is a correctness property of the product —
a user photographing one page on two devices can get two different transcripts —
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
line height 10; padding 25% vertical and 20% horizontal; and the same three
outlier rejections.

**The CLI shares almost none of it**, because it is a different implementation
rather than a port of these: a flat global threshold at 128 instead of an adaptive
one, no morphological smear at all, a fixed 4px pad on both axes, and smoothing 3.

## What does not

|                            | Web                     | Android            | iOS                | CLI                         |
| -------------------------- | ----------------------- | ------------------ | ------------------ | --------------------------- |
| Histogram smoothing kernel | 3                       | **5**              | 3                  | 3                           |
| Grayscale blur             | 3×3                     | **5×5**            | 3×3                | **none**                    |
| Binarizes against          | **unblurred** grayscale | blurred            | blurred            | **global 128**              |
| Density ratio, page mode   | 0.03                    | 0.03               | 0.03               | **0.05**                    |
| Mode selection             | **none**                | provenance + shape | provenance + shape | `--mode`, `auto`, `inspect` |

Two of the first three are Android against the others; the third is web against
the others. So no surface is the reference, and no two agree completely.

**The 0.03-against-0.05 row is a correction.** This document previously listed the
density ratio under "what agrees", which was true of the three apps and never of
the CLI. `apps/cli/src/mode.rs:39` calls 0.05 "the library default" and pins it by
test, so the two values are each deliberate and simply were never compared.

**Web has no mode at all**, which is worth stating beside the rest: it is the one
surface where a fused-block reading cannot be retried, because there is no other
parameter set to retry with. Its `lineShaped` flag also stops at
`console.warn` — the worker posts a bare string — so a user sees fused text as
ordinary output.

**Why it matters more than the numbers suggest.** The smoothing kernel is
applied to the row-ink profile before bands are detected, so it decides directly
whether two closely-set lines merge into one band or stay apart. A larger kernel
merges more. Android merges more readily than web and iOS, on the same page.

## What is not claimed here

**Which one is right.** Nothing has measured these three against a page with a
known line count, so picking a winner would be taste presented as a result. The
upstream trainer, `mon_OCR`, uses different values again — smoothing 15,
adaptive window 31, constant 15, density ratio 0.12 — tuned against rendered
book pages, and its own documentation records that the ratio suits books at 0.12
and posters at 0.50. There is no single correct setting across document types,
which is part of why this is recorded rather than unified.

## A fifth divergence, newer and larger than any above

`mon_OCR`'s segmenter gained printed-rule suppression on 2026-08-27 (step 3.5).
Measured on twelve real MNEC papers, nine collapsed to a single band without it and
seven of those returned 0–2 characters. Pages carrying no rules come back
byte-identical, so it is not a trade-off between document types the way the density
ratio is.

**Of the four surfaces here, only web has it.** Each port was measured through its
own parameter set rather than inheriting the reference figure — bands over the same
twelve pages, without → with:

| surface                      | bands without |         with |
| ---------------------------- | ------------: | -----------: |
| **web**                      |            68 |      **160** |
| iOS                          |            68 | _not ported_ |
| Android                      |            70 | _not ported_ |
| CLI (via `monocr-onnx/rust`) |           118 | _not ported_ |

The three unported ones are blocked by tooling on the machine the work was done
on, not by a decision: the Rust test binary cannot link there (`clang_rt.osx`
missing), iOS needs Xcode where only Command Line Tools are installed, and Gradle
cannot resolve a JDK toolchain offline. Unverified code is not worth shipping to an
app, so they wait rather than being written blind.

An earlier version of this section said _no_ surface had it, which was true for
about an hour.

That makes it different in kind from everything above. The rows in the table are
unresolved because picking a winner needs ground truth nobody has; this one is
unresolved only because it has not been ported. `mon_OCR`'s ROADMAP 4.5.7 tracks
it.

## What would close it

A page set with counted lines, run through all four surfaces. That is the same
missing artifact as `mon_OCR`'s `DATA_STRATEGY.md` rung D2 — real page images
with ground truth — and it closes several open questions at once. Until it
exists, aligning them would be choosing one arbitrary setting over three others
and calling it parity.

That argument does **not** cover step 3.5 above, which needs no ground truth to
justify: a page returning zero characters is measurable without a baseline.
