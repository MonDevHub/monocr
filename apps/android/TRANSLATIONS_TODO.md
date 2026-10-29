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

## Already missing before this change

`capture_soft_warning`, `docs_input_standards_desc`, `nav_intro`,
`segmentation_block_warning`, `segmentation_failed_lines`, `segmentation_mode_hint`,
`segmentation_mode_label`, `segmentation_mode_line`, `segmentation_mode_page`,
`segmentation_mode_sparse`.

`./gradlew :app:lintDebug` reports each of these as `MissingTranslation`.
