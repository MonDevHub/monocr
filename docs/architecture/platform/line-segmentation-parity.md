# Line segmentation: three ports, two real divergences

**Status:** recorded, not resolved. Measured 2026-08-15.

Web, Android and iOS each carry their own port of the same horizontal
projection-profile line segmenter. They are meant to be the same algorithm, and
on two parameters they are not, so **the same page can produce different line
sets on different platforms**. That is a correctness property of the product —
a user photographing one page on two devices can get two different transcripts —
and nothing in the repository currently notices it.

## Where the code is

| Platform | File |
|---|---|
| Web | [`apps/web/src/lib/segmentation.ts`](../../../apps/web/src/lib/segmentation.ts) |
| Android | [`apps/android/app/src/main/java/dev/janakhpon/monocr/engine/LineSegmenter.kt`](../../../apps/android/app/src/main/java/dev/janakhpon/monocr/engine/LineSegmenter.kt) |
| iOS | [`apps/ios/monocr-ios/LineSegmenter.swift`](../../../apps/ios/monocr-ios/LineSegmenter.swift) |

## What agrees

Adaptive threshold window 25 and constant 8; horizontal smear kernel 11;
vertical smear kernel 5; density ratio 0.03; minimum line height 10; padding 25%
vertical and 20% horizontal; and the same three outlier rejections.

## What does not

| | Web | Android | iOS |
|---|---|---|---|
| Histogram smoothing kernel | 3 | **5** | 3 |
| Grayscale blur | 3×3 | **5×5** | 3×3 |
| Binarizes against | **unblurred** grayscale | blurred | blurred |

Two of these are Android against the other two; the third is web against the
other two. So no platform is the reference, and no two agree completely.

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

## What would close it

A page set with counted lines, run through all three ports. That is the same
missing artifact as `mon_OCR`'s `DATA_STRATEGY.md` rung D2 — real page images
with ground truth — and it closes several open questions at once. Until it
exists, aligning the three would be choosing one arbitrary setting over two
others and calling it parity.
