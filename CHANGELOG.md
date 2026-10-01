# Changelog

## Unreleased

### Added

- CLI: `--json` output for a PDF has `expected_pages` and `failed_pages`, so a
  pipeline can tell a partly read PDF from a whole one.

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
- CLI: an input on which every page was read and none has text prints
  `no text found` on stderr. It still exits 0. A page has no text when the
  model returned no lines, or only empty or whitespace lines, so a blank image
  read in line mode is reported too.
- CLI: the README has an "Exit codes" section stating which cases exit 1.
