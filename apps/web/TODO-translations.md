# Translations still needed (web)

These keys exist in `messages/en.json` and not in `messages/my.json` or `messages/mnw.json`, so Burmese and Mon
readers see the English text (Paraglide falls back to the base locale). They were added on 2026-10-29 as English only:
no Mon or Burmese was machine-translated. A Mon speaker supplies `mnw`; a Burmese speaker supplies `my`.
`src/lib/messages.test.ts` fails if a key is missing from both locale files and absent from this list.

| Key                            | English                                                                                                                         |
| ------------------------------ | ------------------------------------------------------------------------------------------------------------------------------- |
| `main_dropzone_title`          | Drop an image or PDF here                                                                                                       |
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
| `adjust_open`                  | Adjust                                                                                                                          |
| `adjust_hint`                  | Rotate the image or drag on it to choose the part to read, then read it again.                                                  |
| `adjust_rotate`                | Rotate 90°                                                                                                                      |
| `adjust_clear_crop`            | Clear crop                                                                                                                      |
| `adjust_apply`                 | Read again                                                                                                                      |
| `adjust_canvas_label`          | Image to rotate or crop                                                                                                         |

Other English that is still hard-coded, not yet a message key:

- Warnings built in `src/lib/reading.ts` ("N line(s) could not be read", "Page(s) ... could not be read", the fused-block and capture notes) and the capture-quality sentences in `src/lib/capture-quality.ts`, which reach the result card through `PageReading`.
- The raw engine error text, shown under Details.
- `Mon (mnw)` values identical to English noted in the audit (about 32, mostly `docs_*`) are untouched here.
