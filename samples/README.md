# Samples

Three real inputs, the text MonOCR returned for each, and the per-line records
behind it. Nothing here is hand-corrected — `output.txt` is exactly what the CLI
wrote.

Produced 2026-08-29 by `apps/cli` at 300 DPI, `monocr-onnx` at `9135cab` and
later, model revision `d3d9d5e` (v3.5).

| sample | input | pages | lines | garbage lines | characters | median line height | speed |
| ------ | ----- | ----: | ----: | ------------: | ---------: | -----------------: | ----: |
| [`mnec-vs-policy`](mnec-vs-policy/) | born-digital PDF | 14 | 226 | **none of 226** | 14,290 | 71 px | 3.3 s/page |
| [`buddha-chronicle`](buddha-chronicle/) | born-digital PDF | 10 | 313 | **none of 313** | 24,049 | 79 px | 7.0 s/page |
| [`typeset-screenshot`](typeset-screenshot/) | 925×1280 JPEG | 1 | 24 | **none of 24** | 1,270 | 40 px | 3.7 s/page |

Each directory holds `input.pdf` or `input.jpg`, `output.txt`, and `lines.jsonl`
— one JSON record per page, carrying every line's text and its bounding box.

## Two things these show that surprise people

**Neither PDF is Unicode.** `mnec-vs-policy` carries a Zawgyi text layer — U+1039
virama appears 1,739 times against 62 of U+103A asat, a 28:1 ratio the wrong way
round. `buddha-chronicle` is legacy 8-bit: its text layer is **0% Myanmar block**,
plain ASCII bytes like `k[.j}tY` that only become Mon through the embedded
`A1Mon` and `Arup` fonts.

It makes no difference. Rasterisation happens before the model, so no encoding
ever reaches it — the model sees glyphs and emits Unicode. Both of these are among
the cleanest results in the set. Where the encoding *does* matter is scoring
against a text layer and labelling training data, and it has corrupted this
project's validation split once already.

**`typeset-screenshot` is not a PDF and not a camera photo.** A screen capture of
typeset Mon at 925×1280, which is a common way people actually have text.

## What "garbage lines" counts, and what it misses

The share of detected lines decoding to more than half Mon digits and longer than
three characters. A failed line reads as a run of `၀၆၄...` because a strip of
glyph tops looks like digits. The length clause matters: page numbers are single
Mon digits read **correctly**, and counting them as failures punishes the tool for
being right.

It is not character accuracy, and it is blind to Latin script. An English document
in the wider screening came through with a clean count while its output read
`thenvention ofTraditions`. Read the outputs; do not take the column on faith.

Counts, not rates, on purpose. `0.0%` rounds, so it reads as *never* when what it
means here is *none of these 563 lines*. Three documents cannot support the first
claim and do support the second.

## These were selected, and that is the point of saying so

Eleven books and six images were screened four pages each and ranked. These three
came out clean. On unscreened material the same build runs closer to **9%** garbage
lines, and one 145-page image scan reached 33%.

So this is what the tool does at its best on material of this kind — not an
average over an archive. What still goes wrong, and by how much, is in
[`../docs/architecture/platform/line-segmentation-parity.md`](../docs/architecture/platform/line-segmentation-parity.md)
and in `mon_OCR`'s `docs/AUDIT-2026-08-B.md`, findings F-69 and F-70.

Expect dropped `်` (asat), an occasional stray Latin letter, and `ဂကောံ` for
`ဂေကာံ`. Nothing here is error-free.

## Reproducing

```bash
cd apps/cli
cargo run --release -- extract ../../samples/mnec-vs-policy/input.pdf -o /tmp/out
```

## Provenance

`mnec-vs-policy` is an MNEC education policy paper by နာဲဗညာဟံသာ.
`buddha-chronicle` is ပြကိုဟ်ဗုဒ္ဓဝၚ်, pages 13–22, from the Mon E-book Library.
Both are included as public Mon-language material at the repository owner's
determination. The full-length originals are not in this repository.
