//! Load and validate `monocr.yaml` into a typed `Config`, failing fast on bad values.
//!
//! WHY A CONFIG FILE AT ALL
//! ------------------------
//! Every option was a flag. That is fine for a one-off run and poor for a
//! repeatable one: a book extraction is a fixed set of choices that belongs under
//! version control, not a shell line to reconstruct from history. `pdf2audio`
//! solves the same problem with a `config.yaml` at its root, and this follows that
//! shape deliberately — sectioned, commented with the *reason* for each value, and
//! validated on load rather than at the point of use.
//!
//! THE MERGE RULE, WHICH IS THE PART THAT NEEDS STATING
//! ----------------------------------------------------
//! **The file is the baseline; a flag is the exception.** So a flag always wins
//! where it can express "unset":
//!
//!   * `--mode`, `--dpi`, `--output` are `Option`, so absent means "take the file's
//!     value" and present means "override it".
//!   * `--recursive`, `--resume`, `--json`, `--dry-run` are switches. A switch
//!     cannot say "false" — clap gives the same `false` whether it was omitted or
//!     could not be passed — so these are OR-ed with the file: **the flag can turn
//!     a setting ON, never OFF.** That asymmetry is deliberate rather than an
//!     oversight. The cases where you reach for the flag are "also do a dry run
//!     this time" and "also descend today", and a switch that could silently
//!     cancel a file setting would make `--dry-run` unsafe to add to a command
//!     line out of habit. To turn one off, edit the file.
//!
//! WHAT IS NOT CONFIGURABLE, AND WHY
//! ---------------------------------
//! Nothing that changes what the *model* sees. The input height, the width, the
//! normalisation and the model revision are one contract with the exported graph,
//! and `mon_OCR/docs/CHARSET.md` records what happened the last time part of that
//! contract moved without the rest. They are not options and this file will not
//! make them look like options.

use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};
use serde::Deserialize;

/// The default file name, looked for in the working directory.
pub const DEFAULT_CONFIG: &str = "monocr.yaml";

/// Rasterisation resolutions outside this range are a typo, not a choice. Below
/// 72 a page renders smaller than the text the segmenter is tuned for; above 1200
/// an A0 sheet allocates tens of gigabytes before anything reads it.
const MIN_DPI: u32 = 72;
const MAX_DPI: u32 = 1200;

#[derive(Debug, Deserialize, Default, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct InputSection {
    /// Files, directories, or a mix. Positional arguments override this entirely.
    #[serde(default)]
    pub paths: Vec<PathBuf>,
    #[serde(default)]
    pub recursive: bool,
}

#[derive(Debug, Deserialize, Default, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct OutputSection {
    #[serde(default)]
    pub path: Option<PathBuf>,
    #[serde(default)]
    pub json: bool,
}

#[derive(Debug, Deserialize, Default, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct SegmentationSection {
    /// `auto`, `page`, `sparse` or `line`. Validated on load, because a typo here
    /// silently changes which density ratio the segmenter runs with.
    #[serde(default)]
    pub mode: Option<String>,
}

#[derive(Debug, Deserialize, Default, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct RenderSection {
    #[serde(default)]
    pub dpi: Option<u32>,
}

#[derive(Debug, Deserialize, Default, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct RunSection {
    #[serde(default)]
    pub resume: bool,
    #[serde(default)]
    pub dry_run: bool,
}

#[derive(Debug, Deserialize, Default, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct Config {
    #[serde(default)]
    pub input: InputSection,
    #[serde(default)]
    pub output: OutputSection,
    #[serde(default)]
    pub segmentation: SegmentationSection,
    #[serde(default)]
    pub render: RenderSection,
    #[serde(default)]
    pub run: RunSection,
}

/// Valid `segmentation.mode` values. Kept here rather than derived from `ModeArg`
/// so a bad value fails while loading the file, with the file's name in the
/// message, instead of somewhere downstream.
pub const VALID_MODES: [&str; 4] = ["auto", "page", "sparse", "line"];

/// Parse and validate a config file.
///
/// `deny_unknown_fields` is set on every section, so a misspelled key is an error
/// rather than a silently ignored line. That matters more than it sounds: a
/// `recursive: true` under the wrong section would otherwise read as "configured"
/// and do nothing, which is the failure mode this ecosystem keeps writing lessons
/// about.
pub fn load(path: &Path) -> Result<Config> {
    let text = std::fs::read_to_string(path)
        .with_context(|| format!("cannot read config file {}", path.display()))?;
    let config: Config = serde_yaml::from_str(&text)
        .with_context(|| format!("cannot parse {} as YAML", path.display()))?;
    validate(&config, path)?;
    Ok(config)
}

fn validate(config: &Config, path: &Path) -> Result<()> {
    if let Some(mode) = &config.segmentation.mode {
        if !VALID_MODES.contains(&mode.as_str()) {
            bail!(
                "{}: segmentation.mode is {:?}, expected one of {}",
                path.display(),
                mode,
                VALID_MODES.join(", ")
            );
        }
    }
    if let Some(dpi) = config.render.dpi {
        if !(MIN_DPI..=MAX_DPI).contains(&dpi) {
            bail!(
                "{}: render.dpi is {}, outside {}..={}. Below {} a page renders \
                 smaller than the text size the segmenter is tuned for; above {} \
                 a large sheet allocates tens of gigabytes before anything reads it",
                path.display(),
                dpi,
                MIN_DPI,
                MAX_DPI,
                MIN_DPI,
                MAX_DPI
            );
        }
    }
    Ok(())
}

/// Resolve which config file to use, if any.
///
/// An explicit `--config` that does not exist is an error: the user named a file
/// and the run would otherwise proceed with defaults, silently ignoring the
/// settings they meant to apply. A *missing default* is not an error, because the
/// tool has to keep working with no config at all.
pub fn resolve(explicit: Option<&Path>) -> Result<Option<Config>> {
    match explicit {
        Some(p) => Ok(Some(load(p)?)),
        None => {
            let default = Path::new(DEFAULT_CONFIG);
            if default.exists() {
                Ok(Some(load(default)?))
            } else {
                Ok(None)
            }
        }
    }
}

/// What the caller passed on the command line. `Option` fields can express
/// "unset"; the `bool` fields cannot, which is what drives the merge rule below.
#[derive(Debug, Default)]
pub struct Flags {
    pub paths: Vec<PathBuf>,
    pub output: Option<PathBuf>,
    pub recursive: bool,
    pub mode: Option<String>,
    pub resume: bool,
    pub json: bool,
    pub dry_run: bool,
    pub dpi: Option<u32>,
}

/// The settings a run will actually use.
#[derive(Debug, PartialEq)]
pub struct Resolved {
    pub paths: Vec<PathBuf>,
    pub output: Option<PathBuf>,
    pub recursive: bool,
    /// `None` means `auto`.
    pub mode: Option<String>,
    pub resume: bool,
    pub json: bool,
    pub dry_run: bool,
    pub dpi: Option<u32>,
}

/// Combine flags with a config file. A pure function of its inputs, so the merge
/// rule can be tested without a filesystem, a model or a subprocess — the same
/// split `mon_OCR/scripts/segmenter_parity.py` uses between its rule layer and
/// its disk I/O.
///
/// See the module header for why switches are OR-ed and `Option`s override.
pub fn merge(flags: Flags, file: Config) -> Resolved {
    Resolved {
        // Positional paths REPLACE the file's list rather than adding to it. A
        // caller naming one file expects that file, not that file plus whatever
        // the config happened to list.
        paths: if flags.paths.is_empty() {
            file.input.paths
        } else {
            flags.paths
        },
        output: flags.output.or(file.output.path),
        recursive: flags.recursive || file.input.recursive,
        mode: flags
            .mode
            .or(file.segmentation.mode)
            .filter(|m| m != "auto"),
        resume: flags.resume || file.run.resume,
        json: flags.json || file.output.json,
        dry_run: flags.dry_run || file.run.dry_run,
        dpi: flags.dpi.or(file.render.dpi),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn write(text: &str) -> tempfile::NamedTempFile {
        use std::io::Write;
        let mut f = tempfile::Builder::new().suffix(".yaml").tempfile().unwrap();
        f.write_all(text.as_bytes()).unwrap();
        f.flush().unwrap();
        f
    }

    // ── the merge rule ───────────────────────────────────────────────────────

    fn cfg(text: &str) -> Config {
        serde_yaml::from_str(text).unwrap()
    }

    #[test]
    fn no_flags_and_no_file_resolves_to_nothing_set() {
        let r = merge(Flags::default(), Config::default());
        assert_eq!(
            r,
            Resolved {
                paths: vec![],
                output: None,
                recursive: false,
                mode: None,
                resume: false,
                json: false,
                dry_run: false,
                dpi: None,
            }
        );
    }

    #[test]
    fn the_file_supplies_values_no_flag_gave() {
        let file = cfg("input:\n  paths: [books]\n  recursive: true\noutput:\n  path: out\n  json: true\nsegmentation:\n  mode: sparse\nrender:\n  dpi: 150\nrun:\n  resume: true\n  dry_run: true\n");
        let r = merge(Flags::default(), file);
        assert_eq!(r.paths, vec![PathBuf::from("books")]);
        assert_eq!(r.output, Some(PathBuf::from("out")));
        assert!(r.recursive && r.json && r.resume && r.dry_run);
        assert_eq!(r.mode.as_deref(), Some("sparse"));
        assert_eq!(r.dpi, Some(150));
    }

    #[test]
    fn an_option_flag_overrides_the_file() {
        let file =
            cfg("segmentation:\n  mode: sparse\nrender:\n  dpi: 150\noutput:\n  path: from_file\n");
        let r = merge(
            Flags {
                mode: Some("page".into()),
                dpi: Some(300),
                output: Some(PathBuf::from("from_flag")),
                ..Default::default()
            },
            file,
        );
        assert_eq!(r.mode.as_deref(), Some("page"));
        assert_eq!(r.dpi, Some(300));
        assert_eq!(r.output, Some(PathBuf::from("from_flag")));
    }

    #[test]
    fn positional_paths_replace_the_files_list_rather_than_adding_to_it() {
        // A caller naming one file expects that file, not that file plus whatever
        // the config happened to list.
        let file = cfg("input:\n  paths: [a, b, c]\n");
        let r = merge(
            Flags {
                paths: vec![PathBuf::from("only")],
                ..Default::default()
            },
            file,
        );
        assert_eq!(r.paths, vec![PathBuf::from("only")]);
    }

    #[test]
    fn a_switch_can_turn_a_setting_on_but_never_off() {
        // THE ASYMMETRY, pinned. clap cannot tell "omitted" from "false", so a
        // switch that could cancel a file setting would make --dry-run unsafe to
        // add to a command line out of habit.
        let file = cfg("run:\n  dry_run: true\n");
        let r = merge(Flags::default(), file);
        assert!(r.dry_run, "the file's dry_run must survive an omitted flag");

        let r = merge(
            Flags {
                dry_run: true,
                ..Default::default()
            },
            Config::default(),
        );
        assert!(r.dry_run, "the flag alone must turn it on");
    }

    #[test]
    fn auto_is_normalised_to_none_from_either_source() {
        // `auto` and absent are the same request, and downstream should not have to
        // know which spelling it arrived as.
        assert_eq!(
            merge(
                Flags {
                    mode: Some("auto".into()),
                    ..Default::default()
                },
                Config::default()
            )
            .mode,
            None
        );
        assert_eq!(
            merge(Flags::default(), cfg("segmentation:\n  mode: auto\n")).mode,
            None
        );
    }

    #[test]
    fn an_empty_file_is_valid_and_all_defaults() {
        // A config that sets nothing must behave exactly like no config, or adding
        // an empty file to a repo would change how the tool runs.
        let f = write("");
        assert_eq!(load(f.path()).unwrap(), Config::default());
    }

    #[test]
    fn sections_are_independent() {
        let f = write("render:\n  dpi: 200\n");
        let c = load(f.path()).unwrap();
        assert_eq!(c.render.dpi, Some(200));
        assert_eq!(c.input.paths, Vec::<PathBuf>::new());
        assert!(!c.run.dry_run);
    }

    #[test]
    fn a_misspelled_key_is_an_error_not_a_silent_no_op() {
        // THE CASE THIS VALIDATION EXISTS FOR. `recursive` under the wrong section
        // reads as "configured" and does nothing, which is the exact failure this
        // ecosystem keeps writing lessons about.
        let f = write("input:\n  recursiv: true\n");
        let err = load(f.path()).unwrap_err().to_string();
        assert!(err.contains("cannot parse"), "unexpected error: {err}");
    }

    #[test]
    fn a_key_in_the_wrong_section_is_an_error() {
        let f = write("render:\n  recursive: true\n");
        assert!(load(f.path()).is_err());
    }

    #[test]
    fn an_invalid_mode_names_the_valid_ones() {
        let f = write("segmentation:\n  mode: pages\n");
        let err = load(f.path()).unwrap_err().to_string();
        assert!(err.contains("expected one of"), "unexpected error: {err}");
        assert!(
            err.contains("sparse"),
            "the message should list the options: {err}"
        );
    }

    #[test]
    fn every_valid_mode_is_accepted() {
        for mode in VALID_MODES {
            let f = write(&format!("segmentation:\n  mode: {mode}\n"));
            assert!(load(f.path()).is_ok(), "{mode} should be valid");
        }
    }

    #[test]
    fn dpi_bounds_are_enforced_at_both_ends() {
        for bad in [0u32, 71, 1201, 100_000] {
            let f = write(&format!("render:\n  dpi: {bad}\n"));
            assert!(load(f.path()).is_err(), "dpi {bad} should be refused");
        }
        for good in [MIN_DPI, 300, MAX_DPI] {
            let f = write(&format!("render:\n  dpi: {good}\n"));
            assert!(load(f.path()).is_ok(), "dpi {good} should be accepted");
        }
    }

    #[test]
    fn malformed_yaml_names_the_file() {
        let f = write("input:\n  paths: [unclosed\n");
        let err = load(f.path()).unwrap_err().to_string();
        assert!(
            err.contains(f.path().to_str().unwrap()),
            "unexpected error: {err}"
        );
    }

    #[test]
    fn an_explicit_missing_config_is_an_error() {
        // The user named a file. Proceeding with defaults would silently ignore
        // every setting they meant to apply.
        let err = resolve(Some(Path::new("definitely-not-here.yaml")))
            .unwrap_err()
            .to_string();
        assert!(
            err.contains("cannot read config file"),
            "unexpected error: {err}"
        );
    }

    #[test]
    fn no_config_anywhere_is_not_an_error() {
        // The tool must keep working with no config at all. Run from a temp dir so
        // a monocr.yaml in the repo cannot make this pass for the wrong reason.
        let dir = tempfile::tempdir().unwrap();
        let cwd = std::env::current_dir().unwrap();
        std::env::set_current_dir(dir.path()).unwrap();
        let got = resolve(None);
        std::env::set_current_dir(cwd).unwrap();
        assert!(matches!(got, Ok(None)));
    }
}

#[cfg(test)]
mod mode_pairing {
    // `as_config_str` and `from_config_str` live in main.rs, and VALID_MODES lives
    // here. Three lists of the same four names is two chances to drift, so this
    // pins that the loader accepts exactly what the enum can round-trip.
    use super::VALID_MODES;

    #[test]
    fn valid_modes_are_the_four_the_cli_knows() {
        assert_eq!(VALID_MODES, ["auto", "page", "sparse", "line"]);
    }

    #[test]
    fn every_valid_mode_round_trips_through_the_merge() {
        for m in VALID_MODES {
            let r = super::merge(
                super::Flags {
                    mode: Some(m.to_string()),
                    ..Default::default()
                },
                super::Config::default(),
            );
            let expected = if m == "auto" { None } else { Some(m) };
            assert_eq!(
                r.mode.as_deref(),
                expected,
                "mode {m} did not survive the merge"
            );
        }
    }
}
