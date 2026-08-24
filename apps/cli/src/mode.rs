//! Which segmentation regime an input gets, and why.
//!
//! One parameter set does not serve every input. `mon_OCR/docs/LIMITATIONS.md`
//! §304-334 measured the ordering reversing between input classes: on book pages
//! the low gap ratio recovered 89.0% of known 5-grams against 87.1% at 0.50,
//! while a six-line Mon poem slide returned 3 lines at the low ratio and all 6,
//! read correctly, at 0.50. The response is explicitly non-monotone — on one
//! photograph 0.5 gave 5 lines, 0.7 gave 4 and 1.3 gave 1 — so this is a choice
//! about the input, not a constant anyone can settle by tuning.

use std::fmt;
use std::path::Path;

use crate::discover::InputKind;

/// The model's input height. A crop shorter than a small multiple of this is
/// already a line, and segmenting a line fragments it.
const MODEL_INPUT_HEIGHT: u32 = 160;

/// Below this height an image may be an already-cropped line. Taken from
/// `mon-corpus-scraper`, which uses `height > MODEL_H * 2` for the same call.
///
/// Height alone is not sufficient, which the fixture set demonstrates:
/// `pdf_screenshot.png` is 876x277, so it passes this test, and at aspect 3.2 it
/// is plainly several lines rather than one. Every genuine single line in that
/// set runs aspect 8.6 to 18.3. So the aspect test below has to agree.
const LINE_HEIGHT_CEILING: u32 = MODEL_INPUT_HEIGHT * 2;

/// Minimum width-to-height ratio for a crop to be one line.
///
/// The same 4.0 the canonical `looks_like_a_line` uses for the same judgement
/// (`mon_OCR/src/monocr/segmenter.py:181-215`). Reused rather than re-picked: two
/// constants for one question drift apart, and this ecosystem has a documented
/// history of exactly that.
const LINE_MIN_ASPECT: f64 = 4.0;

/// Density ratio for a document page. This is the library default, so `Page`
/// leaves existing behaviour exactly as it was.
const PAGE_DENSITY_RATIO: f32 = 0.05;

/// Density ratio for a sparse input: a photograph, poster, slide or sign.
/// From the LIMITATIONS measurement above. It is not a better value than the
/// page ratio; it is the better value for a different kind of picture.
const SPARSE_DENSITY_RATIO: f32 = 0.50;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Mode {
    /// Multi-line document: a PDF render, a flatbed scan, a screenshot of a page.
    Page,
    /// Few lines, uneven lighting: a camera photo, poster, slide or sign.
    Sparse,
    /// The input is already one cropped line. Skip segmentation, tile, recognise.
    Line,
}

impl Mode {
    pub fn density_ratio(self) -> Option<f32> {
        match self {
            Mode::Page => Some(PAGE_DENSITY_RATIO),
            Mode::Sparse => Some(SPARSE_DENSITY_RATIO),
            // Line mode never segments, so the ratio is not consulted.
            Mode::Line => None,
        }
    }

    pub fn segments(self) -> bool {
        !matches!(self, Mode::Line)
    }
}

impl fmt::Display for Mode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let s = match self {
            Mode::Page => "page",
            Mode::Sparse => "sparse",
            Mode::Line => "line",
        };
        f.write_str(s)
    }
}

/// How the mode for an input was arrived at, so `inspect` can explain itself and
/// a surprising run is diagnosable without a rerun.
#[derive(Debug, Clone)]
pub struct Decision {
    pub mode: Mode,
    pub reason: String,
}

/// Choose a mode for one input.
///
/// `Sparse` is never selected automatically for a file on disk. A photograph and
/// a scan are indistinguishable by inspection, and LIMITATIONS records what
/// guessing wrong costs: on a whiteboard photo five lines fused into one band and
/// the recogniser returned fluent Mon that appears nowhere on the page, at
/// confidence 0.83. Since confidence does not separate that case from a good
/// read, it cannot drive the choice — so the automatic path stays conservative
/// and `--mode sparse` stays a thing the operator asks for.
pub fn decide(kind: InputKind, path: &Path, dimensions: Option<(u32, u32)>) -> Decision {
    match kind {
        InputKind::Pdf => Decision {
            mode: Mode::Page,
            reason: "PDF page render, which is known to be a document page".to_string(),
        },
        InputKind::Image => match dimensions {
            None => Decision {
                mode: Mode::Page,
                reason: format!(
                    "could not read the dimensions of {}; defaulting to page",
                    path.display()
                ),
            },
            Some((w, h)) if h == 0 || w == 0 => Decision {
                mode: Mode::Page,
                reason: format!("{}x{h} has no area; defaulting to page", w),
            },
            Some((w, h)) => {
                let aspect = f64::from(w) / f64::from(h);
                let short_enough = h < LINE_HEIGHT_CEILING;
                let line_shaped = aspect >= LINE_MIN_ASPECT;

                // Both tests must agree. Short-and-wide is a line; short-and-
                // squarish is a small block of several lines, and reading it as
                // one line concatenates them into nonsense.
                if short_enough && line_shaped {
                    Decision {
                        mode: Mode::Line,
                        reason: format!(
                            "{w}x{h}, aspect {aspect:.1}: short enough and line-shaped, \
                             so it is already a single line"
                        ),
                    }
                } else if short_enough {
                    Decision {
                        mode: Mode::Page,
                        reason: format!(
                            "{w}x{h} is short but aspect {aspect:.1} is under \
                             {LINE_MIN_ASPECT:.1}, so it is a block of lines rather than one line"
                        ),
                    }
                } else {
                    Decision {
                        mode: Mode::Page,
                        reason: format!(
                            "{w}x{h}: a file on disk is treated as a page unless told \
                             otherwise, because a photo and a scan look the same from here"
                        ),
                    }
                }
            }
        },
    }
}

/// Is this band a block of text rather than a line?
///
/// Ported from `mon_OCR/src/monocr/segmenter.py:181-215`. It filters nothing —
/// it flags. Over 66 measured bands it fired 3 times, all on the two unreadable
/// camera photos, with no false positives. A caller shows it to the operator as
/// "this looks fused, try another mode"; it must not silently drop a band.
pub fn looks_like_a_line(width: u32, height: u32, page_height: u32) -> bool {
    if height == 0 || page_height == 0 {
        return false;
    }
    let fills_the_page = f64::from(height) > f64::from(page_height) * 0.40;
    let line_shaped = f64::from(width) / f64::from(height) >= LINE_MIN_ASPECT;
    line_shaped || !fills_the_page
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_pdf_is_always_a_page() {
        let d = decide(InputKind::Pdf, Path::new("book.pdf"), None);
        assert_eq!(d.mode, Mode::Page);
    }

    #[test]
    fn a_short_wide_image_is_an_already_cropped_line() {
        let d = decide(InputKind::Image, Path::new("line.png"), Some((1024, 90)));
        assert_eq!(d.mode, Mode::Line);
        assert!(!d.mode.segments());
    }

    /// The real fixture set, measured. Every genuine single line in
    /// monocr-onnx/data/images runs aspect 8.6 to 18.3; pdf_screenshot.png is
    /// 876x277, aspect 3.2, and is several lines. Height alone put it in Line
    /// mode, which would have concatenated its lines into one string.
    #[test]
    fn the_real_fixture_images_are_classified_correctly() {
        let line_shaped = [
            ("000028.jpg", 1024, 64),
            ("000029.jpg", 1024, 64),
            ("test_0005_h71.png", 1633, 93),
            ("test_0006_h61.png", 712, 83),
            ("test_0011_h30.png", 711, 51),
            ("test_0012_h86.png", 1995, 109),
        ];
        for (name, w, h) in line_shaped {
            assert_eq!(
                decide(InputKind::Image, Path::new(name), Some((w, h))).mode,
                Mode::Line,
                "{name} ({w}x{h}) is a single line"
            );
        }

        let block = decide(
            InputKind::Image,
            Path::new("pdf_screenshot.png"),
            Some((876, 277)),
        );
        assert_eq!(
            block.mode,
            Mode::Page,
            "876x277 at aspect 3.2 is a block of lines, not one line"
        );
        assert!(
            block.reason.contains("aspect"),
            "reason should name the aspect test"
        );
    }

    #[test]
    fn the_line_ceiling_is_exclusive() {
        // Exactly 2x the model height is a page: the boundary has to fall on one
        // side and a 320px strip is more plausibly two lines than one.
        assert_eq!(
            decide(InputKind::Image, Path::new("x.png"), Some((4000, 320))).mode,
            Mode::Page
        );
        assert_eq!(
            decide(InputKind::Image, Path::new("x.png"), Some((4000, 319))).mode,
            Mode::Line
        );
    }

    #[test]
    fn both_tests_must_agree_for_line_mode() {
        // Short but squarish: a block.
        assert_eq!(
            decide(InputKind::Image, Path::new("x.png"), Some((300, 200))).mode,
            Mode::Page
        );
        // Line-shaped but tall: a page that happens to be wide.
        assert_eq!(
            decide(InputKind::Image, Path::new("x.png"), Some((8000, 1000))).mode,
            Mode::Page
        );
        // Exactly at the aspect boundary counts as a line.
        assert_eq!(
            decide(InputKind::Image, Path::new("x.png"), Some((400, 100))).mode,
            Mode::Line
        );
    }

    #[test]
    fn a_zero_area_image_does_not_divide_by_zero() {
        assert_eq!(
            decide(InputKind::Image, Path::new("x.png"), Some((0, 100))).mode,
            Mode::Page
        );
        assert_eq!(
            decide(InputKind::Image, Path::new("x.png"), Some((100, 0))).mode,
            Mode::Page
        );
    }

    #[test]
    fn sparse_is_never_chosen_automatically() {
        for (w, h) in [
            (1u32, 1u32),
            (1024, 100),
            (4000, 319),
            (900, 320),
            (600, 1000),
            (5000, 5000),
        ] {
            let d = decide(InputKind::Image, Path::new("x.png"), Some((w, h)));
            assert_ne!(d.mode, Mode::Sparse, "auto-selected sparse at {w}x{h}");
        }
        assert_ne!(
            decide(InputKind::Pdf, Path::new("x.pdf"), None).mode,
            Mode::Sparse
        );
    }

    #[test]
    fn page_mode_keeps_the_library_default_ratio() {
        // Page mode must not be a behaviour change: it is today's segmentation
        // plus tiling, nothing else.
        assert_eq!(Mode::Page.density_ratio(), Some(0.05));
        assert_eq!(Mode::Sparse.density_ratio(), Some(0.50));
        assert_eq!(Mode::Line.density_ratio(), None);
    }

    #[test]
    fn unreadable_dimensions_fall_back_to_page_rather_than_failing() {
        let d = decide(InputKind::Image, Path::new("broken.png"), None);
        assert_eq!(d.mode, Mode::Page);
        assert!(d.reason.contains("could not read"));
    }

    #[test]
    fn looks_like_a_line_flags_a_fused_block() {
        // The LIMITATIONS case: a 493px band on a 760px page, five lines fused.
        assert!(!looks_like_a_line(600, 493, 760));
        // A normal line on the same page.
        assert!(looks_like_a_line(1800, 70, 760));
        // Degenerate input is not a finding.
        assert!(!looks_like_a_line(100, 0, 760));
        assert!(!looks_like_a_line(100, 50, 0));
    }
}
