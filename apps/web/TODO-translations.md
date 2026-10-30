# Translations still needed (web)

These keys exist in `messages/en.json` and not in `messages/my.json` or `messages/mnw.json`, so Burmese and Mon
readers see the English text (Paraglide falls back to the base locale). They were added on 2026-10-29 as English only:
no Mon or Burmese was machine-translated. A Mon speaker supplies `mnw`; a Burmese speaker supplies `my`.
`src/lib/messages.test.ts` fails if a key is missing from both locale files and absent from this list.

| Key                            | English                                                                                                                         |
| ------------------------------ | ------------------------------------------------------------------------------------------------------------------------------- |
| `main_dropzone_title`          | Or drop an image or PDF here                                                                                                    |
| `main_title`                   | Read text from an image or PDF                                                                                                  |
| `main_choose_file`             | Choose a file                                                                                                                   |
| `main_report_problem`          | Report a problem                                                                                                                |
| `error_label`                  | Error                                                                                                                           |
| `note_label`                   | Note                                                                                                                            |
| `main_dropzone_hint`           | Or click to browse from your device                                                                                             |
| `main_dropzone_blocked`        | The OCR engine did not load, so files cannot be read yet. Retry loading it above.                                               |
| `main_queued`                  | Waiting for the model. {name} will start when it is ready.                                                                      |
| `main_cancel`                  | Cancel                                                                                                                          |
| `main_clear_result`            | Clear result                                                                                                                    |
| `main_no_text_hint`            | Try a sharper or closer capture of a page with printed text.                                                                    |
| `main_preview_alt`             | Preview of the selected file                                                                                                    |
| `main_learn_more`              | Learn more                                                                                                                      |
| `error_details`                | Details                                                                                                                         |
| `a11y_skip_to_main`            | Skip to main content                                                                                                            |
| `theme_to_dark`                | Toggle dark mode                                                                                                                |
| `theme_to_light`               | Toggle light mode                                                                                                               |
| `lang_label`                   | Language                                                                                                                        |
| `report_original_output`       | Original Output                                                                                                                 |
| `report_corrected_text`        | Corrected Text                                                                                                                  |
| `report_human_verification`    | Human Verification                                                                                                              |
| `report_error_type`            | Error Type                                                                                                                      |
| `report_type_spelling`         | Spelling                                                                                                                        |
| `report_type_layout`           | Layout                                                                                                                          |
| `report_type_formatting`       | Formatting                                                                                                                      |
| `report_type_other`            | Other                                                                                                                           |
| `report_quality_hint`          | Report quality issues to help improve our model                                                                                 |
| `report_source_alt`            | Source scan                                                                                                                     |
| `report_corrected_placeholder` | Corrected Mon script...                                                                                                         |
| `report_consent_label`         | I want to help improve MonOCR                                                                                                   |
| `report_consent_desc`          | Allow this correction to be used for future model training and verification. We respect your privacy according to our policies. |
| `report_share`                 | Share Correction                                                                                                                |
| `report_sharing`               | Sharing...                                                                                                                      |
| `report_past_feedback`         | Past Feedback                                                                                                                   |
| `report_received_title`        | Feedback Received                                                                                                               |
| `report_received_msg`          | Thanks for helping us improve MonOCR! Your correction has been saved.                                                           |
| `contribute_or`                | OR                                                                                                                              |
| `contribute_type_script`       | Type Mon Script                                                                                                                 |
| `contribute_placeholder`       | Example: မန်ဒိုင် (Type or paste the Mon script here)...                                                                        |
| `contribute_submit`            | Submit Contribution                                                                                                             |
| `contribute_submitting`        | Submitting...                                                                                                                   |
| `contribute_history`           | Contributions                                                                                                                   |
| `contribute_received_title`    | Contribution Received                                                                                                           |
| `contribute_received_msg`      | Thank you for contributing to the Mon language community! Your submission has been saved.                                       |
| `adjust_open`                  | Rotate and crop                                                                                                                 |
| `adjust_hint`                  | Rotate the image or drag on it to choose the part to read, then read it again.                                                  |
| `adjust_rotate`                | Rotate 90°                                                                                                                      |
| `adjust_clear_crop`            | Clear crop                                                                                                                      |
| `adjust_apply`                 | Read again                                                                                                                      |
| `adjust_canvas_label`          | Image to rotate or crop                                                                                                         |
| `error_page_not_found_title`   | This page does not exist                                                                                                        |
| `error_page_not_found_text`    | The address may be mistyped, or the page may have moved. Go back to Home, or read the docs.                                     |
| `error_page_generic_title`     | Something went wrong                                                                                                            |
| `error_page_generic_text`      | An unexpected error stopped this page from loading. Go back to Home and try again, or read the docs.                            |
| `error_page_home`              | Go to Home                                                                                                                      |
| `error_page_docs`              | Read the docs                                                                                                                   |
| `form_choose_file`             | Choose file                                                                                                                     |
| `form_remove_file`             | Remove file                                                                                                                     |
| `form_error_title`             | There is a problem                                                                                                              |
| `report_error_text`            | Enter the corrected text, then share again.                                                                                     |
| `report_error_consent`         | Tick the consent box to share your correction, then share again.                                                                |
| `contribute_error_empty`       | Add a document or type Mon script, then submit again.                                                                           |
| `nav_main`                     | Main                                                                                                                            |
| `footer_links`                 | Project links                                                                                                                   |
| `error_save_failed`            | Could not save this. Try again, and if it keeps failing, reload the page.                                                       |
| `nav_menu`                     | Menu                                                                                                                            |     |

Other English that is still hard-coded, not yet a message key:

- The whole privacy page (`src/routes/privacy/+page.svelte`): headings, the short version, every section, the date line. It was literal English before the redesign too; it is the page Burmese and Mon readers most need, so it is the first candidate for a native translation.
- The docs page's code captions ("Shell", "example.py", "Terminal"), its sentences, and the Copy failed word.
- Units and saved record names: `MB`, `ms`, and the titles `Feedback: <file>`, `Contribute: ...` and `Mon Text Contribution` shown in the history list.
- Warnings built in `src/lib/reading.ts` ("N line(s) could not be read", "Page(s) ... could not be read", the fused-block and capture notes) and the capture-quality sentences in `src/lib/capture-quality.ts`, which reach the result card through `PageReading`.
- The raw engine error text, shown under Details.
- `Mon (mnw)` values identical to English noted in the audit (about 32, mostly `docs_*`) are untouched here.

English that was reworded in this release, where `my` and `mnw` still carry the old wording (the keys are translated, so nothing flags them): each now says the next step, uses sentence case, or names what is lost. A native speaker should check the existing translations still say the same thing.

- `error_file_large`, `error_file_type`, `error_engine_load`, `error_pdf_failed`, `error_ocr_failed`: now add what to do next.
- `main_process_another` ("Read another file"), `main_save_text` ("Download text"), `adjust_open` ("Rotate and crop"), `history_clear_all` ("Clear history"), `history_close_view` ("Close view").
- `modal_confirm_delete_msg`: now says the records are deleted from this browser and cannot be recovered.
- `main_words`, `main_chars` and the other `main_*` and `history_*` labels set in sentence case.
