//! Whether a read succeeded, and what the exit code says about a run.
//!
//! Kept apart from `main.rs` so the rules are plain functions of counts, tested
//! here on their own. The wiring that feeds them the counts is tested in
//! `extract_tests.rs`, against a fake reader in place of the model.
//!
//! The rules, which the README's "Exit codes" section states for users:
//!
//! - An input with no page read is a **failure**. Every page was tried and every
//!   one failed, so nothing in the output reflects the input.
//! - An input with some pages read and some failed is a **warning**. The pages
//!   that were read are written, each failed page has its own manifest record,
//!   and the exit code does not change.
//! - An input that was read but has no text on it is a **success**. A blank page
//!   is an answer, not an error.
//! - The run exits 1 when at least one input failed, and 0 otherwise.

/// What happened to the pages of one input.
#[derive(Debug, Default)]
pub struct PageTally {
    read: usize,
    with_text: usize,
    failed: Vec<usize>,
    first_error: Option<String>,
}

impl PageTally {
    /// A page that was recognised. `has_text` is false when the model found no
    /// lines, or only blank ones, which still counts as read.
    pub fn read(&mut self, has_text: bool) {
        self.read += 1;
        if has_text {
            self.with_text += 1;
        }
    }

    /// A page that could not be rendered or recognised.
    pub fn failed(&mut self, page: usize, error: String) {
        self.failed.push(page);
        self.first_error.get_or_insert(error);
    }

    pub fn failed_pages(&self) -> &[usize] {
        &self.failed
    }

    pub fn verdict(&self) -> Verdict {
        match (self.read, self.failed.len()) {
            // Stopped by Ctrl-C before the first page. The interrupt reports it.
            (0, 0) => Verdict::NotStarted,
            (0, failed) => Verdict::Unread {
                failed,
                first_error: self.first_error.clone().unwrap_or_default(),
            },
            (read, 0) if self.with_text == 0 => Verdict::NoText { read },
            (_, 0) => Verdict::Read,
            (read, _) => Verdict::Partial {
                read,
                failed: self.failed.clone(),
            },
        }
    }
}

/// The result for one input, judged from its pages.
#[derive(Debug, PartialEq, Eq)]
pub enum Verdict {
    /// Every page tried was read, and at least one had text.
    Read,
    /// Every page tried was read, and none had text.
    NoText { read: usize },
    /// Some pages were read and some failed.
    Partial { read: usize, failed: Vec<usize> },
    /// Pages were tried and none was read.
    Unread { failed: usize, first_error: String },
    /// No page was tried.
    NotStarted,
}

impl Verdict {
    /// Whether this input counts towards the run's failures and exit code 1.
    pub fn is_failure(&self) -> bool {
        matches!(self, Verdict::Unread { .. })
    }

    /// The line to print on stderr, if there is one. For a failure it is the
    /// error message.
    pub fn message(&self) -> Option<String> {
        match self {
            Verdict::Read | Verdict::NotStarted => None,
            Verdict::NoText { read } => Some(format!(
                "no text found on {read} page(s); this is not an error"
            )),
            Verdict::Partial { read, failed } => Some(format!(
                "warning: {} of {} page(s) could not be read (page {}); the other pages \
                 were written, each failed page is in manifest.jsonl, and --resume \
                 reads this input again",
                failed.len(),
                read + failed.len(),
                join_pages(failed)
            )),
            Verdict::Unread {
                failed,
                first_error,
            } => Some(format!(
                "none of the {failed} page(s) could be read; the first error was: {first_error}"
            )),
        }
    }
}

/// How the run as a whole ends, before Ctrl-C is taken into account.
#[derive(Debug, PartialEq, Eq)]
pub enum RunEnd {
    /// Exit 0, with an optional warning to print last.
    Success { warning: Option<String> },
    /// Exit 1, with this message as the error.
    Failure(String),
}

/// Judge the run from how many inputs failed and how many were only partly read.
pub fn run_end(failed: usize, partial: usize, total: usize) -> RunEnd {
    if failed > 0 {
        return RunEnd::Failure(format!(
            "{failed} of {total} input(s) failed; see manifest.jsonl"
        ));
    }
    let warning = (partial > 0).then(|| {
        format!(
            "warning: {partial} of {total} input(s) were only partly read; \
             see manifest.jsonl for the pages that failed"
        )
    });
    RunEnd::Success { warning }
}

fn join_pages(pages: &[usize]) -> String {
    // A book with hundreds of bad pages should not print hundreds of numbers on
    // one line; the manifest has the full list.
    const SHOWN: usize = 10;
    let mut s = pages
        .iter()
        .take(SHOWN)
        .map(|p| p.to_string())
        .collect::<Vec<_>>()
        .join(", ");
    if pages.len() > SHOWN {
        s.push_str(&format!(", and {} more", pages.len() - SHOWN));
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tally(read_with_text: usize, read_blank: usize, failed: &[usize]) -> PageTally {
        let mut t = PageTally::default();
        for _ in 0..read_with_text {
            t.read(true);
        }
        for _ in 0..read_blank {
            t.read(false);
        }
        for &p in failed {
            t.failed(p, format!("page {p} broke"));
        }
        t
    }

    #[test]
    fn an_input_where_every_page_failed_is_a_failure() {
        let v = tally(0, 0, &[1, 2, 3]).verdict();
        assert_eq!(
            v,
            Verdict::Unread {
                failed: 3,
                first_error: "page 1 broke".to_string()
            }
        );
        assert!(v.is_failure());
        let msg = v.message().expect("a failure has a message");
        assert!(msg.contains("none of the 3 page(s)"), "{msg}");
        // The first error, not the last, so the cause an operator reads is the
        // one that happened first.
        assert!(msg.contains("page 1 broke"), "{msg}");
    }

    #[test]
    fn a_single_failed_page_with_nothing_read_is_a_failure() {
        assert!(tally(0, 0, &[1]).verdict().is_failure());
    }

    #[test]
    fn an_input_where_some_pages_failed_is_a_warning_not_a_failure() {
        let v = tally(2, 1, &[2, 5]).verdict();
        assert_eq!(
            v,
            Verdict::Partial {
                read: 3,
                failed: vec![2, 5]
            }
        );
        assert!(!v.is_failure());
        let msg = v.message().expect("a partial read is reported");
        assert!(msg.starts_with("warning:"), "{msg}");
        assert!(msg.contains("2 of 5 page(s)"), "{msg}");
        assert!(msg.contains("page 2, 5"), "{msg}");
    }

    #[test]
    fn some_pages_failed_and_the_rest_were_blank_is_still_a_warning() {
        let v = tally(0, 2, &[3]).verdict();
        assert!(matches!(v, Verdict::Partial { read: 2, .. }), "{v:?}");
        assert!(!v.is_failure());
    }

    #[test]
    fn a_read_page_with_no_text_is_not_a_failure() {
        let v = tally(0, 1, &[]).verdict();
        assert_eq!(v, Verdict::NoText { read: 1 });
        assert!(!v.is_failure());
        let msg = v.message().expect("no text is said out loud");
        assert!(msg.contains("not an error"), "{msg}");
    }

    #[test]
    fn one_page_with_text_among_blank_ones_is_a_normal_read() {
        let v = tally(1, 4, &[]).verdict();
        assert_eq!(v, Verdict::Read);
        assert!(!v.is_failure());
        assert_eq!(v.message(), None);
    }

    #[test]
    fn an_input_interrupted_before_its_first_page_is_not_a_failure() {
        let v = tally(0, 0, &[]).verdict();
        assert_eq!(v, Verdict::NotStarted);
        assert!(!v.is_failure());
        assert_eq!(v.message(), None);
    }

    #[test]
    fn a_long_list_of_failed_pages_is_cut_short() {
        let failed: Vec<usize> = (1..=25).collect();
        let msg = tally(1, 0, &failed).verdict().message().unwrap();
        assert!(msg.contains("10, and 15 more"), "{msg}");
        assert!(!msg.contains("11"), "{msg}");
    }

    #[test]
    fn a_run_with_a_failed_input_exits_as_a_failure() {
        assert_eq!(
            run_end(1, 0, 3),
            RunEnd::Failure("1 of 3 input(s) failed; see manifest.jsonl".to_string())
        );
        // A failure outranks partial reads elsewhere in the batch.
        assert!(matches!(run_end(2, 5, 9), RunEnd::Failure(_)));
    }

    #[test]
    fn a_run_with_only_partial_reads_succeeds_with_a_warning() {
        match run_end(0, 2, 4) {
            RunEnd::Success { warning: Some(w) } => {
                assert!(w.starts_with("warning:"), "{w}");
                assert!(w.contains("2 of 4 input(s)"), "{w}");
            }
            other => panic!("expected success with a warning, got {other:?}"),
        }
    }

    #[test]
    fn a_clean_run_succeeds_without_a_warning() {
        assert_eq!(run_end(0, 0, 4), RunEnd::Success { warning: None });
    }
}
