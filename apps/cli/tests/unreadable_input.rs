//! An input the CLI cannot open fails that input, not the batch.
//!
//! Runs the built binary, because the defect was in the order of calls inside
//! `extract`: the content digest opened each file with `?`, so one file without
//! read permission ended the run before any manifest record was written, and
//! every input after it was never tried. Nothing here loads the model: the file
//! is refused before a session is built, which is also why this test needs no
//! network.

#![cfg(unix)]

use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::process::Command;

fn lock(path: &Path) {
    std::fs::write(path, b"bytes that are never read").unwrap();
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o000)).unwrap();
    // Root ignores file permissions, so this fixture cannot be unreadable there.
    // Failing is deliberate: returning early would report a pass for a test that
    // asserted nothing.
    assert!(
        std::fs::File::open(path).is_err(),
        "{} is still readable after chmod 000, probably because the tests run as \
         root. This test needs an unprivileged user.",
        path.display()
    );
}

#[test]
fn unreadable_inputs_are_each_recorded_and_the_run_exits_1() {
    let dir = tempfile::tempdir().unwrap();
    let first = dir.path().join("a.png");
    let second = dir.path().join("b.png");
    lock(&first);
    lock(&second);
    let out = dir.path().join("out");

    let run = Command::new(env!("CARGO_BIN_EXE_monocr-cli"))
        .arg("extract")
        .arg(&first)
        .arg(&second)
        .arg("--output")
        .arg(&out)
        .output()
        .unwrap();

    // Restore permissions so the temp dir can be removed.
    for p in [&first, &second] {
        std::fs::set_permissions(p, std::fs::Permissions::from_mode(0o600)).unwrap();
    }

    let stderr = String::from_utf8_lossy(&run.stderr);
    assert_eq!(run.status.code(), Some(1), "stderr:\n{stderr}");
    assert!(run.stdout.is_empty(), "stdout carries results only");

    // Both inputs were tried: the first did not end the run.
    assert!(
        stderr.contains("error: 2 of 2 input(s) failed"),
        "stderr:\n{stderr}"
    );
    assert!(stderr.contains("cannot open input"), "stderr:\n{stderr}");
    assert!(
        !stderr.contains("loading the model"),
        "an input that cannot be opened must not cost a model load\n{stderr}"
    );

    let manifest = std::fs::read_to_string(out.join("manifest.jsonl")).unwrap();
    let failures: Vec<serde_json::Value> = manifest
        .lines()
        .map(|l| serde_json::from_str(l).unwrap())
        .filter(|v: &serde_json::Value| v["kind"] == "failure")
        .collect();
    assert_eq!(failures.len(), 2, "manifest:\n{manifest}");
    for (record, path) in failures.iter().zip([&first, &second]) {
        assert_eq!(record["input"], path.display().to_string());
        assert!(record["page"].is_null());
        assert!(
            record["error"]
                .as_str()
                .unwrap()
                .contains("cannot open input"),
            "{record}"
        );
    }
}
