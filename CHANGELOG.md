# Changelog

## Unreleased

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

Lifecycle behaviour on physical Android and iOS devices has not been tested yet.
