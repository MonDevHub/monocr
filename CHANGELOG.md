# Changelog

## Unreleased

### Fixed

- CLI: an input file that cannot be opened is recorded as a failure in
  `manifest.jsonl` and the batch continues. It used to end the run before any
  later input was read, with no manifest record.
- CLI: a PDF page that cannot be rendered or read is named on stderr and
  recorded in `manifest.jsonl` with its page number, and the other pages are
  still read and written. One bad page used to fail the whole PDF.

### Changed

- CLI: a PDF with some unreadable pages exits 0 with a warning, and is not
  recorded as finished, so `--resume` reads it again. A PDF in which no page
  could be read still exits 1.
- CLI: an input with no text on it prints `no text found` on stderr. It still
  exits 0.
- CLI: the README has an "Exit codes" section stating which cases exit 1.
