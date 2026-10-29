# iOS strings waiting for a Burmese or Mon translation

Written by `python3 Scripts/check-strings.py --update-todo`; do not edit by hand.

Each key below is shown in English to Burmese or Mon readers until a native speaker
translates it. Nothing here was machine-translated, and nothing should be: add the
translation to the translations sheet that `pnpm translate` reads
(`shared/locales/sync.mjs`, ADR 0002), run it, then rerun this script with
`--update-todo`. A Mon value identical to the English counts as untranslated,
because that is what the sync writes when the sheet's Mon cell is empty.

The check (`Scripts/check-strings.py`, run by `Scripts/swift-test.sh`) fails on a
new untranslated key that is not listed here, and on a listed key that has since
been translated or is no longer used.

## Needs Burmese (my): 94

- "%1$lld of %2$lld pages have not been read; this reading is incomplete." (OcrOutcomes.swift)
- "%lld block(s) were too tall to be one line. Their text may be invented — try the Sparse or Line mode." (OcrOutcomes.swift)
- "%lld line(s) could not be read completely. Check the source image." (OcrOutcomes.swift)
- "%lld line(s) may have missing text around a gap. Check the source image." (OcrOutcomes.swift)
- "%lld words, %lld characters, %@ seconds" (ResultCardView.swift)
- "1. Scans stay on your device" (PrivacyView.swift)
- "2. Scan history" (PrivacyView.swift)
- "3. Feedback and contributions are uploaded" (PrivacyView.swift)
- "5. Changes to Policy" (PrivacyView.swift)
- "Allow camera access for MonOCR in Settings to photograph pages." (CameraPermission.swift)
- "Camera access is off" (CameraPermission.swift)
- "Camera access is restricted" (CameraPermission.swift)
- "Camera not available" (CameraPermission.swift)
- "Camera use is restricted on this device, so MonOCR cannot open it." (CameraPermission.swift)
- "Cancel" (CameraPermission.swift, ContentView.swift)
- "Changes to this policy will be published in the project's open-source repository and on this screen." (PrivacyView.swift)
- "Contributors" (DocsView.swift)
- "Could not access the PDF file." (UserMessage.swift)
- "Could not open the file: %@" (UserMessage.swift)
- "Could not prepare the page for reading." (MonOcrEngine.swift)
- "Could not read the image pixels." (MonOcrEngine.swift)
- "Crafted by MonDevHub" (AboutView.swift)
- "Delete" (HistoryViews.swift)
- "Delete this item?" (HistoryViews.swift)
- "Dense text: scans, book pages, PDFs." (SegmentationMode.swift)
- "Double tap to view details" (HistoryViews.swift)
- "Effective Date: October 29, 2026" (PrivacyView.swift)
- "Extracted Text" (ResultCardView.swift)
- "Failed to initialize OCR engine: %@" (UserMessage.swift)
- "Feedback" (AboutView.swift, SideMenuView.swift)
- "Feedback and Contribute are the only screens that send anything. When you submit one, its text and any image or PDF attached to it are uploaded to the MonDevHub feedback service, where they are used to check and improve the recognition model. Feedback starts with the scan you are reporting attached; you can change or remove it. Both screens ask for your consent first, and nothing is sent until you submit. Each submission is also kept in that screen's history, which shows whether it has been uploaded." (PrivacyView.swift)
- "Files (PDF)" (ContributeView.swift, FeedbackView.swift)
- "Follow these standards to give the model the best chance of reading a page." (DocsView.swift)
- "History is temporarily unavailable. New scans will not be kept after closing the app." (monocr_iosApp.swift)
- "Hugging Face Models" (AboutView.swift)
- "If you have any questions, please contact the MonDevHub engineering team via our public open source repositories on GitHub." (PrivacyView.swift)
- "Image Detail" (FullScreenImageView.swift)
- "Line" (SegmentationMode.swift)
- "Menu" (ContentView.swift)
- "Mon OCR" (ContentView.swift)
- "MonOCR" (AboutView.swift, ContentView.swift, SideMenuView.swift)
- "MonOCR reads text on your device with Core ML. The images, PDFs and camera photos you scan, and the text read from them, are not sent anywhere unless you include them in Feedback or a contribution (section 3). There is no account and no analytics." (PrivacyView.swift)
- "NPM Package" (AboutView.swift)
- "New scan" (ContentView.swift)
- "No detected line could be read completely." (OcrOutcomes.swift)
- "No page of this PDF could be read." (UserMessage.swift)
- "No text could be read." (UserMessage.swift)
- "No text was read on one or more pages. Check the source; this does not confirm a blank page." (OcrOutcomes.swift)
- "OK" (CameraPermission.swift, CommonViews.swift)
- "On-device OCR for the Mon language. Recognition runs locally with Apple Core ML, and your scans never leave your device." (AboutView.swift)
- "Open Settings" (CameraPermission.swift)
- "PDF or Images" (ContributeView.swift)
- "Page" (SegmentationMode.swift)
- "Pages could not be read: %@." (OcrOutcomes.swift)
- "Photo Library" (ContributeView.swift, FeedbackView.swift)
- "Processing cancelled. Nothing was saved." (UserMessage.swift)
- "Processing cancelled. The pages read so far are in history." (UserMessage.swift)
- "Processing was cancelled; this reading is incomplete." (OcrOutcomes.swift)
- "PyPI Package" (AboutView.swift)
- "Reading: %lld of %lld pages done" (ContentView.swift, EngineStatusHeaderView.swift)
- "Recognition failed: %@" (MonOcrEngine.swift, UserMessage.swift)
- "Recognition runs entirely on your device. Scans are never uploaded; only Feedback and Contributions you choose to submit are." (DocsView.swift)
- "Rendering PDF..." (HistoryViews.swift)
- "SDK" (DocsView.swift)
- "Save" (ResultCardView.swift)
- "Scans are saved in the app's history on this device so you can open them again. You can delete them from the history at any time, and deleting the app deletes them." (PrivacyView.swift)
- "Segmentation" (SegmentationModePicker.swift)
- "Share" (ResultCardView.swift)
- "Sparse" (SegmentationMode.swift)
- "Submitting uploads the text and any attached image or PDF to the MonDevHub feedback service, where they are used to check and improve the model." (CommonViews.swift)
- "Text regions were not detected. The whole-page reading needs review against the source." (OcrOutcomes.swift)
- "Thank you" (CommonViews.swift)
- "The PDF could not be opened. It may be invalid or no longer accessible." (PdfUtil.swift)
- "The page could not be rendered." (OcrOutcomes.swift)
- "The recognizer returned an invalid tensor shape." (CtcDecoder.swift)
- "The recognizer returned nonfinite scores." (CtcDecoder.swift)
- "The whole image is one line of text." (SegmentationMode.swift)
- "This PDF has no pages to read." (PdfUtil.swift)
- "This PDF is larger than 20 MB. Choose a smaller file, or send a page as an image." (UserMessage.swift)
- "This PDF is locked. Unlock it before importing it." (PdfUtil.swift)
- "This build cannot use the bundled model: %@" (MonOcrEngine.swift)
- "This device has no camera. Choose a photo or a PDF instead." (CameraPermission.swift)
- "This image could not be opened. Try another one." (UserMessage.swift)
- "This photo looks soft. The text may be misread — try again with steadier focus." (OcrOutcomes.swift)
- "To maintain privacy compliance, we do not use third-party cloud crash reporters. Diagnostic logs are written locally. You are in full control and can export them voluntarily if you encounter bugs." (PrivacyView.swift)
- "Upload failed" (HistoryViews.swift)
- "Wide gaps: photos, posters, slides." (SegmentationMode.swift)
- "Your submission is saved and will be uploaded when you are online. The history below shows when it has been." (CommonViews.swift)
- "Your submission is saved on this device. This build is not set up to upload submissions." (CommonViews.swift)
- "charset.txt could not be read." (MonOcrEngine.swift)
- "charset.txt is empty." (MonOcrEngine.swift)
- "charset.txt is missing from the app bundle." (MonOcrEngine.swift)
- "monocr (GitHub)" (AboutView.swift)
- "© 2026 Janakhpon · MIT License" (AboutView.swift)

## Needs Mon (mnw): 114

- "%1$lld of %2$lld pages have not been read; this reading is incomplete." (OcrOutcomes.swift)
- "%lld block(s) were too tall to be one line. Their text may be invented — try the Sparse or Line mode." (OcrOutcomes.swift)
- "%lld line(s) could not be read completely. Check the source image." (OcrOutcomes.swift)
- "%lld line(s) may have missing text around a gap. Check the source image." (OcrOutcomes.swift)
- "%lld words, %lld characters, %@ seconds" (ResultCardView.swift)
- "1. Scans stay on your device" (PrivacyView.swift)
- "2. Scan history" (PrivacyView.swift)
- "3. Feedback and contributions are uploaded" (PrivacyView.swift)
- "5. Changes to Policy" (PrivacyView.swift)
- "Allow camera access for MonOCR in Settings to photograph pages." (CameraPermission.swift)
- "Application" (AboutView.swift)
- "Camera access is off" (CameraPermission.swift)
- "Camera access is restricted" (CameraPermission.swift)
- "Camera not available" (CameraPermission.swift)
- "Camera use is restricted on this device, so MonOCR cannot open it." (CameraPermission.swift)
- "Cancel" (CameraPermission.swift, ContentView.swift)
- "Changes to this policy will be published in the project's open-source repository and on this screen." (PrivacyView.swift)
- "Contributors" (DocsView.swift)
- "Could not access the PDF file." (UserMessage.swift)
- "Could not open the file: %@" (UserMessage.swift)
- "Could not prepare the page for reading." (MonOcrEngine.swift)
- "Could not read the image pixels." (MonOcrEngine.swift)
- "Crafted by MonDevHub" (AboutView.swift)
- "Delete" (HistoryViews.swift)
- "Delete this item?" (HistoryViews.swift)
- "Dense text: scans, book pages, PDFs." (SegmentationMode.swift)
- "Diffuse lighting, no glares." (DocsView.swift)
- "Double tap to view details" (HistoryViews.swift)
- "Effective Date: October 29, 2026" (PrivacyView.swift)
- "Engine ready" (EngineStatusHeaderView.swift)
- "Export Debug Logs" (AboutView.swift)
- "Extracted Text" (ResultCardView.swift)
- "Failed to initialize OCR engine: %@" (UserMessage.swift)
- "Feedback" (AboutView.swift, SideMenuView.swift)
- "Feedback and Contribute are the only screens that send anything. When you submit one, its text and any image or PDF attached to it are uploaded to the MonDevHub feedback service, where they are used to check and improve the recognition model. Feedback starts with the scan you are reporting attached; you can change or remove it. Both screens ask for your consent first, and nothing is sent until you submit. Each submission is also kept in that screen's history, which shows whether it has been uploaded." (PrivacyView.swift)
- "File size limit exceeded" (ContentView.swift)
- "File too large (Max 50MB). Use CLI tools or desktop version for bigger file support." (UserMessage.swift)
- "Files (PDF)" (ContributeView.swift, FeedbackView.swift)
- "Follow these standards to give the model the best chance of reading a page." (DocsView.swift)
- "History is temporarily unavailable. New scans will not be kept after closing the app." (monocr_iosApp.swift)
- "Hugging Face Models" (AboutView.swift)
- "If you have any questions, please contact the MonDevHub engineering team via our public open source repositories on GitHub." (PrivacyView.swift)
- "Image Detail" (FullScreenImageView.swift)
- "Initializing engine..." (EngineStatusHeaderView.swift)
- "Line" (SegmentationMode.swift)
- "Menu" (ContentView.swift)
- "Model Information" (AboutView.swift)
- "Mon OCR" (ContentView.swift)
- "MonOCR" (AboutView.swift, ContentView.swift, SideMenuView.swift)
- "MonOCR reads text on your device with Core ML. The images, PDFs and camera photos you scan, and the text read from them, are not sent anywhere unless you include them in Feedback or a contribution (section 3). There is no account and no analytics." (PrivacyView.swift)
- "NPM Package" (AboutView.swift)
- "New scan" (ContentView.swift)
- "No detected line could be read completely." (OcrOutcomes.swift)
- "No page of this PDF could be read." (UserMessage.swift)
- "No text could be read." (UserMessage.swift)
- "No text was read on one or more pages. Check the source; this does not confirm a blank page." (OcrOutcomes.swift)
- "OK" (CameraPermission.swift, CommonViews.swift)
- "On-device OCR for printed Mon text. Works offline." (DocsView.swift)
- "On-device OCR for the Mon language. Recognition runs locally with Apple Core ML, and your scans never leave your device." (AboutView.swift)
- "Open Settings" (CameraPermission.swift)
- "Original Output" (FeedbackView.swift)
- "Our production weights and multi-format exports are hosted on Hugging Face." (DocsView.swift)
- "PDF" (ActionButtonsView.swift)
- "PDF or Images" (ContributeView.swift)
- "Page" (SegmentationMode.swift)
- "Pages could not be read: %@." (OcrOutcomes.swift)
- "Photo Library" (ContributeView.swift, FeedbackView.swift)
- "Privacy Policy" (AboutView.swift, PrivacyView.swift)
- "Processing cancelled. Nothing was saved." (UserMessage.swift)
- "Processing cancelled. The pages read so far are in history." (UserMessage.swift)
- "Processing was cancelled; this reading is incomplete." (OcrOutcomes.swift)
- "PyPI Package" (AboutView.swift)
- "Reading: %lld of %lld pages done" (ContentView.swift, EngineStatusHeaderView.swift)
- "Recognition failed: %@" (MonOcrEngine.swift, UserMessage.swift)
- "Recognition runs entirely on your device. Scans are never uploaded; only Feedback and Contributions you choose to submit are." (DocsView.swift)
- "Rendering PDF..." (HistoryViews.swift)
- "Resolution" (DocsView.swift)
- "Resources" (AboutView.swift)
- "SDK" (DocsView.swift)
- "Save" (ResultCardView.swift)
- "Scan Result" (HistoryViews.swift)
- "Scans are saved in the app's history on this device so you can open them again. You can delete them from the history at any time, and deleting the app deletes them." (PrivacyView.swift)
- "Segmentation" (SegmentationModePicker.swift)
- "Select an image or use the camera to extract Mon text." (ContentView.swift)
- "Share" (ResultCardView.swift)
- "Sparse" (SegmentationMode.swift)
- "Submitting uploads the text and any attached image or PDF to the MonDevHub feedback service, where they are used to check and improve the model." (CommonViews.swift)
- "Text Content" (HistoryViews.swift)
- "Text regions were not detected. The whole-page reading needs review against the source." (OcrOutcomes.swift)
- "Thank you" (CommonViews.swift)
- "The PDF could not be opened. It may be invalid or no longer accessible." (PdfUtil.swift)
- "The page could not be rendered." (OcrOutcomes.swift)
- "The recognizer returned an invalid tensor shape." (CtcDecoder.swift)
- "The recognizer returned nonfinite scores." (CtcDecoder.swift)
- "The whole image is one line of text." (SegmentationMode.swift)
- "This PDF has no pages to read." (PdfUtil.swift)
- "This PDF is larger than 20 MB. Choose a smaller file, or send a page as an image." (UserMessage.swift)
- "This PDF is locked. Unlock it before importing it." (PdfUtil.swift)
- "This build cannot use the bundled model: %@" (MonOcrEngine.swift)
- "This device has no camera. Choose a photo or a PDF instead." (CameraPermission.swift)
- "This image could not be opened. Try another one." (UserMessage.swift)
- "This photo looks soft. The text may be misread — try again with steadier focus." (OcrOutcomes.swift)
- "This will permanently delete all scans in this category." (HistoryViews.swift)
- "To maintain privacy compliance, we do not use third-party cloud crash reporters. Diagnostic logs are written locally. You are in full control and can export them voluntarily if you encounter bugs." (PrivacyView.swift)
- "Upload failed" (HistoryViews.swift)
- "Visit Hugging Face" (DocsView.swift)
- "Wide gaps: photos, posters, slides." (SegmentationMode.swift)
- "Your submission is saved and will be uploaded when you are online. The history below shows when it has been." (CommonViews.swift)
- "Your submission is saved on this device. This build is not set up to upload submissions." (CommonViews.swift)
- "charset.txt could not be read." (MonOcrEngine.swift)
- "charset.txt is empty." (MonOcrEngine.swift)
- "charset.txt is missing from the app bundle." (MonOcrEngine.swift)
- "monocr (GitHub)" (AboutView.swift)
- "© 2026 Janakhpon · MIT License" (AboutView.swift)

## Machine-readable list

```json
{
  "%1$lld of %2$lld pages have not been read; this reading is incomplete.": [
    "my",
    "mnw"
  ],
  "%lld block(s) were too tall to be one line. Their text may be invented — try the Sparse or Line mode.": [
    "my",
    "mnw"
  ],
  "%lld line(s) could not be read completely. Check the source image.": [
    "my",
    "mnw"
  ],
  "%lld line(s) may have missing text around a gap. Check the source image.": [
    "my",
    "mnw"
  ],
  "%lld words, %lld characters, %@ seconds": [
    "my",
    "mnw"
  ],
  "1. Scans stay on your device": [
    "my",
    "mnw"
  ],
  "2. Scan history": [
    "my",
    "mnw"
  ],
  "3. Feedback and contributions are uploaded": [
    "my",
    "mnw"
  ],
  "5. Changes to Policy": [
    "my",
    "mnw"
  ],
  "Allow camera access for MonOCR in Settings to photograph pages.": [
    "my",
    "mnw"
  ],
  "Application": [
    "mnw"
  ],
  "Camera access is off": [
    "my",
    "mnw"
  ],
  "Camera access is restricted": [
    "my",
    "mnw"
  ],
  "Camera not available": [
    "my",
    "mnw"
  ],
  "Camera use is restricted on this device, so MonOCR cannot open it.": [
    "my",
    "mnw"
  ],
  "Cancel": [
    "my",
    "mnw"
  ],
  "Changes to this policy will be published in the project's open-source repository and on this screen.": [
    "my",
    "mnw"
  ],
  "Contributors": [
    "my",
    "mnw"
  ],
  "Could not access the PDF file.": [
    "my",
    "mnw"
  ],
  "Could not open the file: %@": [
    "my",
    "mnw"
  ],
  "Could not prepare the page for reading.": [
    "my",
    "mnw"
  ],
  "Could not read the image pixels.": [
    "my",
    "mnw"
  ],
  "Crafted by MonDevHub": [
    "my",
    "mnw"
  ],
  "Delete": [
    "my",
    "mnw"
  ],
  "Delete this item?": [
    "my",
    "mnw"
  ],
  "Dense text: scans, book pages, PDFs.": [
    "my",
    "mnw"
  ],
  "Diffuse lighting, no glares.": [
    "mnw"
  ],
  "Double tap to view details": [
    "my",
    "mnw"
  ],
  "Effective Date: October 29, 2026": [
    "my",
    "mnw"
  ],
  "Engine ready": [
    "mnw"
  ],
  "Export Debug Logs": [
    "mnw"
  ],
  "Extracted Text": [
    "my",
    "mnw"
  ],
  "Failed to initialize OCR engine: %@": [
    "my",
    "mnw"
  ],
  "Feedback": [
    "my",
    "mnw"
  ],
  "Feedback and Contribute are the only screens that send anything. When you submit one, its text and any image or PDF attached to it are uploaded to the MonDevHub feedback service, where they are used to check and improve the recognition model. Feedback starts with the scan you are reporting attached; you can change or remove it. Both screens ask for your consent first, and nothing is sent until you submit. Each submission is also kept in that screen's history, which shows whether it has been uploaded.": [
    "my",
    "mnw"
  ],
  "File size limit exceeded": [
    "mnw"
  ],
  "File too large (Max 50MB). Use CLI tools or desktop version for bigger file support.": [
    "mnw"
  ],
  "Files (PDF)": [
    "my",
    "mnw"
  ],
  "Follow these standards to give the model the best chance of reading a page.": [
    "my",
    "mnw"
  ],
  "History is temporarily unavailable. New scans will not be kept after closing the app.": [
    "my",
    "mnw"
  ],
  "Hugging Face Models": [
    "my",
    "mnw"
  ],
  "If you have any questions, please contact the MonDevHub engineering team via our public open source repositories on GitHub.": [
    "my",
    "mnw"
  ],
  "Image Detail": [
    "my",
    "mnw"
  ],
  "Initializing engine...": [
    "mnw"
  ],
  "Line": [
    "my",
    "mnw"
  ],
  "Menu": [
    "my",
    "mnw"
  ],
  "Model Information": [
    "mnw"
  ],
  "Mon OCR": [
    "my",
    "mnw"
  ],
  "MonOCR": [
    "my",
    "mnw"
  ],
  "MonOCR reads text on your device with Core ML. The images, PDFs and camera photos you scan, and the text read from them, are not sent anywhere unless you include them in Feedback or a contribution (section 3). There is no account and no analytics.": [
    "my",
    "mnw"
  ],
  "NPM Package": [
    "my",
    "mnw"
  ],
  "New scan": [
    "my",
    "mnw"
  ],
  "No detected line could be read completely.": [
    "my",
    "mnw"
  ],
  "No page of this PDF could be read.": [
    "my",
    "mnw"
  ],
  "No text could be read.": [
    "my",
    "mnw"
  ],
  "No text was read on one or more pages. Check the source; this does not confirm a blank page.": [
    "my",
    "mnw"
  ],
  "OK": [
    "my",
    "mnw"
  ],
  "On-device OCR for printed Mon text. Works offline.": [
    "mnw"
  ],
  "On-device OCR for the Mon language. Recognition runs locally with Apple Core ML, and your scans never leave your device.": [
    "my",
    "mnw"
  ],
  "Open Settings": [
    "my",
    "mnw"
  ],
  "Original Output": [
    "mnw"
  ],
  "Our production weights and multi-format exports are hosted on Hugging Face.": [
    "mnw"
  ],
  "PDF": [
    "mnw"
  ],
  "PDF or Images": [
    "my",
    "mnw"
  ],
  "Page": [
    "my",
    "mnw"
  ],
  "Pages could not be read: %@.": [
    "my",
    "mnw"
  ],
  "Photo Library": [
    "my",
    "mnw"
  ],
  "Privacy Policy": [
    "mnw"
  ],
  "Processing cancelled. Nothing was saved.": [
    "my",
    "mnw"
  ],
  "Processing cancelled. The pages read so far are in history.": [
    "my",
    "mnw"
  ],
  "Processing was cancelled; this reading is incomplete.": [
    "my",
    "mnw"
  ],
  "PyPI Package": [
    "my",
    "mnw"
  ],
  "Reading: %lld of %lld pages done": [
    "my",
    "mnw"
  ],
  "Recognition failed: %@": [
    "my",
    "mnw"
  ],
  "Recognition runs entirely on your device. Scans are never uploaded; only Feedback and Contributions you choose to submit are.": [
    "my",
    "mnw"
  ],
  "Rendering PDF...": [
    "my",
    "mnw"
  ],
  "Resolution": [
    "mnw"
  ],
  "Resources": [
    "mnw"
  ],
  "SDK": [
    "my",
    "mnw"
  ],
  "Save": [
    "my",
    "mnw"
  ],
  "Scan Result": [
    "mnw"
  ],
  "Scans are saved in the app's history on this device so you can open them again. You can delete them from the history at any time, and deleting the app deletes them.": [
    "my",
    "mnw"
  ],
  "Segmentation": [
    "my",
    "mnw"
  ],
  "Select an image or use the camera to extract Mon text.": [
    "mnw"
  ],
  "Share": [
    "my",
    "mnw"
  ],
  "Sparse": [
    "my",
    "mnw"
  ],
  "Submitting uploads the text and any attached image or PDF to the MonDevHub feedback service, where they are used to check and improve the model.": [
    "my",
    "mnw"
  ],
  "Text Content": [
    "mnw"
  ],
  "Text regions were not detected. The whole-page reading needs review against the source.": [
    "my",
    "mnw"
  ],
  "Thank you": [
    "my",
    "mnw"
  ],
  "The PDF could not be opened. It may be invalid or no longer accessible.": [
    "my",
    "mnw"
  ],
  "The page could not be rendered.": [
    "my",
    "mnw"
  ],
  "The recognizer returned an invalid tensor shape.": [
    "my",
    "mnw"
  ],
  "The recognizer returned nonfinite scores.": [
    "my",
    "mnw"
  ],
  "The whole image is one line of text.": [
    "my",
    "mnw"
  ],
  "This PDF has no pages to read.": [
    "my",
    "mnw"
  ],
  "This PDF is larger than 20 MB. Choose a smaller file, or send a page as an image.": [
    "my",
    "mnw"
  ],
  "This PDF is locked. Unlock it before importing it.": [
    "my",
    "mnw"
  ],
  "This build cannot use the bundled model: %@": [
    "my",
    "mnw"
  ],
  "This device has no camera. Choose a photo or a PDF instead.": [
    "my",
    "mnw"
  ],
  "This image could not be opened. Try another one.": [
    "my",
    "mnw"
  ],
  "This photo looks soft. The text may be misread — try again with steadier focus.": [
    "my",
    "mnw"
  ],
  "This will permanently delete all scans in this category.": [
    "mnw"
  ],
  "To maintain privacy compliance, we do not use third-party cloud crash reporters. Diagnostic logs are written locally. You are in full control and can export them voluntarily if you encounter bugs.": [
    "my",
    "mnw"
  ],
  "Upload failed": [
    "my",
    "mnw"
  ],
  "Visit Hugging Face": [
    "mnw"
  ],
  "Wide gaps: photos, posters, slides.": [
    "my",
    "mnw"
  ],
  "Your submission is saved and will be uploaded when you are online. The history below shows when it has been.": [
    "my",
    "mnw"
  ],
  "Your submission is saved on this device. This build is not set up to upload submissions.": [
    "my",
    "mnw"
  ],
  "charset.txt could not be read.": [
    "my",
    "mnw"
  ],
  "charset.txt is empty.": [
    "my",
    "mnw"
  ],
  "charset.txt is missing from the app bundle.": [
    "my",
    "mnw"
  ],
  "monocr (GitHub)": [
    "my",
    "mnw"
  ],
  "© 2026 Janakhpon · MIT License": [
    "my",
    "mnw"
  ]
}
```
