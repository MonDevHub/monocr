# Strings waiting for a native Burmese (`my`) and Mon (`mnw`) translation

These keys in `app/src/main/res/values/strings.xml` have no entry in `values-my/` or
`values-mnw/`, so Burmese and Mon users see the English until a native speaker adds one.
They were not machine-translated on purpose. Add both translations, then delete the row.

## Changed meaning: the old translation was removed because it was no longer true

The old Burmese and Mon text said that Feedback and Contribute data never leaves the device.
The app uploads it once submitted, so the translation was removed rather than left in place.

| Key | Note |
| --- | --- |
| `privacy_s1_title` | Section title: what stays on the device |
| `privacy_s1_desc` | |
| `privacy_s2_desc` | Says NNAPI where available, otherwise the CPU (not "NPU") |
| `privacy_s3_desc` | Says what Feedback and Contribute upload and when |
| `privacy_s5_desc` | |
| `docs_privacy_desc` | |
| `docs_privacy_stat` | |
| `about_overview_desc` | |
| `feedback_consent_desc` | Now says that submitting uploads the file and text |

## New strings

| Key | Note |
| --- | --- |
| `contribute_consent_desc` | Under the consent box on Contribute |
| `error_file_unreadable` | Scan error: the file could not be opened |
| `error_pdf_no_pages` | Scan error: the PDF has no pages |
| `error_model_runtime` | Scan error: the model failed on this device |
| `privacy_banner_title`, `privacy_banner_desc` | Banner on the privacy screen, was hard-coded English and claimed no data leaves the phone |
| `intro_welcome_title`, `intro_welcome_desc` | First-run intro, slide 1 (was hard-coded English) |
| `intro_home_title`, `intro_home_desc` | Intro slide 2 |
| `intro_docs_title`, `intro_docs_desc` | Intro slide 3 |
| `intro_community_title`, `intro_community_desc` | Intro slide 4 |
| `intro_get_started`, `intro_continue` | Intro buttons |
| `nav_back` | Back button label for TalkBack |
| `history_original_scan` | TalkBack label of the image in a history entry |
| `page_number` | TalkBack label of a PDF page, "Page %1$d" |
| `pdf_preview_unavailable` | |
| `drawer_version` | "Version %1$s" under the drawer title |
| `docs_contributors` | Docs section title |
| `opens_in_browser` | TalkBack suffix on About links, "%1$s, opens in browser" |
| `save_done`, `save_failed` | Snackbar after Save |
| `camera_unavailable` | Snackbar when no camera app can take the picture |
| `intro_page_state` | TalkBack state of the intro indicator, "Page %1$d of %2$d" |
| `scan_progress` | "Reading page %1$d of %2$d" while a PDF is read |

## Already missing before this change

`capture_soft_warning`, `docs_input_standards_desc`, `nav_intro`,
`segmentation_block_warning`, `segmentation_failed_lines`, `segmentation_mode_hint`,
`segmentation_mode_label`, `segmentation_mode_line`, `segmentation_mode_page`,
`segmentation_mode_sparse`.

`./gradlew :app:lintDebug` reports each of these as `MissingTranslation`.
