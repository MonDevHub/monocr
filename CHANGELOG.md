# Changelog

## Unreleased

### Added

- CLI: `--json` output for a PDF has `expected_pages` and `failed_pages`, so a
  pipeline can tell a partly read PDF from a whole one.

### Changed

- Android: warnings about the reading (soft photo, merged or failed lines) are
  shown for PDF results too.
- Android and iOS: these warnings, and pages that could not be read, are kept
  with the history record and shown when it is reopened.
- iOS: a part of a line that cannot be read no longer fails the whole scan. That
  line is flagged for review and the rest of the page is kept.
- iOS: moving the app to the background cancels a running scan. The pages of a
  PDF read so far stay in history, and the message says whether anything was
  kept.
- CLI: a PDF with some unreadable pages exits 0 with a warning, and is not
  recorded as finished, so `--resume` reads it again. A PDF in which no page
  could be read still exits 1.
- CLI: an input on which every page was read and none has text prints
  `no text found` on stderr. It still exits 0. A page has no text when the
  model returned no lines, or only empty or whitespace lines, so a blank image
  read in line mode is reported too.
- CLI: the README has an "Exit codes" section stating which cases exit 1.

### Fixed

- Android: an older image or PDF selection can no longer replace a newer one.
- Android: a PDF in which no page could be read shows an error instead of an
  empty result. A page that cannot be rendered or read is named in a warning
  instead of being skipped without notice, and no longer fails the whole PDF.
- Android: images with a mirrored or transposed EXIF orientation are read
  upright, and transparent images are read on a white background.
- Android: a damaged cached model is detected and replaced at start.
- Android: if hardware-accelerated recognition fails, it is retried on the CPU.
- Android: a page on which some lines could not be read and the rest read no
  text is kept with a warning that it is incomplete. It used to fail with a
  message saying every line had failed. Only a page on which every line failed
  is an error, as on iOS and the web.
- iOS: history is kept when its store cannot be opened, instead of being
  deleted. The app uses temporary storage for the session and says so.
- iOS: a locked, empty or unreadable PDF gets its own error message.
- iOS: transparent images are read on a white background.
- Web: recognizer output with NaN or infinite scores, or with the wrong number
  of values, fails that line instead of being decoded. Output that was entirely
  NaN used to show as a blank page; a scan in which no line could be read, or a
  PDF in which no page could be read, now shows an error.
- Web: a line that cannot be read no longer fails the whole image, and a page
  that cannot be rendered or read no longer fails the whole PDF. The rest is
  kept, and a warning says which lines or pages are missing. A page that times
  out restarts the engine before the next page is read, and after two timeouts
  the remaining pages are reported missing rather than waited on. An image
  that times out also restarts the engine, so the next image does not wait
  behind it.
- CLI: an input file that cannot be opened is recorded as a failure in
  `manifest.jsonl` and the batch continues. It used to end the run before any
  later input was read, with no manifest record.
- CLI: a PDF page that cannot be rendered or read is named on stderr and
  recorded in `manifest.jsonl` with its page number, and the other pages are
  still read and written. One bad page used to fail the whole PDF.

Lifecycle behaviour on physical Android and iOS devices has not been tested yet.
