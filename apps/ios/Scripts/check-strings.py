#!/usr/bin/env python3
"""Check that every user-facing string in the iOS app can be translated.

Two failures this exists for, both measured on 2026-10-29:

1. A key the Swift code uses that is not in Localizable.xcstrings. It can never
   be translated, because the translation sheet that `pnpm translate` reads
   (shared/locales/sync.mjs) is only ever compared against the catalogue. There
   were 13 such NSLocalizedString keys and 7 SwiftUI literals.
2. A key with no Burmese or no Mon translation, or a Mon "translation" that is
   the English text copied across (sync.mjs writes English into every language
   the sheet leaves empty, and marks it translated). Those have to be known and
   listed, not discovered by a Mon reader.

What counts as a key, read from monocr-ios/*.swift with comments removed:

- the first string literal of NSLocalizedString, AppLocalization.string/format,
  LocalizedStringKey and UserMessage(key:);
- the first literal of a SwiftUI initialiser that takes a LocalizedStringKey
  (Text, Label, Button, Toggle, Picker, Section, ContentUnavailableView, Link,
  Menu, LabeledContent, ProgressView) and of the modifiers navigationTitle,
  accessibilityLabel, accessibilityHint, accessibilityValue, confirmationDialog,
  alert, and accessibilityAction(named:);
- a literal passed as title:, subtitle:, placeholder:, description:, content:
  or message: to this app's own views, which all take LocalizedStringKey;
- every literal returned from a `var ...Key: String` / `var key: String` body,
  and every `static let ...Error = "..."`: the keys the core builds at runtime.

An interpolated literal ("Page \\(n)") matches a catalogue key with a format
specifier in each interpolation's place, which is how SwiftUI derives the key.

Untranslated keys are allowed only while they are listed in
apps/ios/TRANSLATIONS-TODO.md, which this script writes (--update-todo). It
fails on a listed key that is no longer untranslated or no longer used, so the
list cannot rot into a list of things already done.

Usage:
    python3 Scripts/check-strings.py               # check; exit 1 on a problem
    python3 Scripts/check-strings.py --update-todo # rewrite the TODO list
"""

import json
import re
import sys
from pathlib import Path

IOS = Path(__file__).resolve().parent.parent
SOURCES = IOS / "monocr-ios"
CATALOGUE = SOURCES / "Localizable.xcstrings"
TODO = IOS / "TRANSLATIONS-TODO.md"
LANGUAGES = ("my", "mnw")
NAMES = {"my": "Burmese", "mnw": "Mon"}

LITERAL = r'"((?:[^"\\\n]|\\.)*)"'
PATTERNS = [
    r"\b(?:NSLocalizedString|AppLocalization\.string|AppLocalization\.format|LocalizedStringKey)\(\s*" + LITERAL,
    r"\bUserMessage\(\s*key:\s*" + LITERAL,
    r"\bkey:\s*" + LITERAL,
    r"\b(?:Text|Label|Button|Toggle|Picker|Section|ContentUnavailableView|Link|Menu|LabeledContent|ProgressView)\(\s*" + LITERAL,
    r"\.(?:navigationTitle|accessibilityLabel|accessibilityHint|accessibilityValue|confirmationDialog|alert)\(\s*" + LITERAL,
    r"\.accessibilityAction\(\s*named:\s*" + LITERAL,
    r"\b(?:title|subtitle|placeholder|description|content|message):\s*" + LITERAL,
    r"\bstatic let \w+Error\s*=\s*" + LITERAL,
]
KEY_BODY = re.compile(r"\bvar (?:key|\w+Key): String \{")
SPECIFIER = r"%(?:\d+\$)?(?:@|lld|ld|d|lf|f|lu|u)"


def strip_comments(source: str) -> str:
    """Remove // and /* */ comments, leaving string literals alone."""
    out, i, n = [], 0, len(source)
    in_string = False
    while i < n:
        c = source[i]
        if in_string:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(source[i + 1])
                i += 2
                continue
            if c == '"' or c == "\n":
                in_string = False
            i += 1
        elif source.startswith("//", i):
            j = source.find("\n", i)
            i = n if j == -1 else j
        elif source.startswith("/*", i):
            j = source.find("*/", i + 2)
            i = n if j == -1 else j + 2
        elif source.startswith('"""', i):
            # Multi-line literals are code samples here, never keys.
            j = source.find('"""', i + 3)
            i = n if j == -1 else j + 3
        else:
            if c == '"':
                in_string = True
            out.append(c)
            i += 1
    return "".join(out)


def unescape(literal: str) -> str:
    def repl(match):
        body = match.group(1)
        if body.startswith("u{"):
            return chr(int(body[2:-1], 16))
        return {"n": "\n", "t": "\t", "\\": "\\", '"': '"', "'": "'", "0": "\0"}.get(body, "\\" + body)

    return re.sub(r"\\(u\{[0-9a-fA-F]+\}|.)", repl, literal)


def key_bodies(source: str):
    """The text of every `var ...Key: String { ... }` body."""
    for match in KEY_BODY.finditer(source):
        depth, i = 1, match.end()
        while i < len(source) and depth:
            depth += {"{": 1, "}": -1}.get(source[i], 0)
            i += 1
        yield source[match.end():i]


def extract():
    """Map each key (or interpolation pattern) to the files that use it."""
    found = {}
    for path in sorted(SOURCES.glob("*.swift")):
        source = strip_comments(path.read_text(encoding="utf-8"))
        literals = []
        for pattern in PATTERNS:
            literals += re.findall(pattern, source)
        for body in key_bodies(source):
            literals += re.findall(LITERAL, body)
        for literal in literals:
            if not literal.strip():
                continue
            found.setdefault(literal, set()).add(path.name)
    return found


def resolve(literal: str, catalogue: dict):
    """The catalogue key a source literal stands for, or None if there is none."""
    if "\\(" not in literal:
        key = unescape(literal)
        return key if key in catalogue else None
    parts, depth, current, i = [], 0, "", 0
    while i < len(literal):
        if depth == 0 and literal.startswith("\\(", i):
            parts.append(current)
            current, depth, i = "", 1, i + 2
            continue
        if depth:
            depth += {"(": 1, ")": -1}.get(literal[i], 0)
        else:
            current += literal[i]
        i += 1
    parts.append(current)
    pattern = re.compile("^" + SPECIFIER.join(re.escape(unescape(p)) for p in parts) + "$")
    matches = [key for key in catalogue if pattern.match(key)]
    return matches[0] if matches else None


def english(key: str, entry: dict) -> str:
    unit = entry.get("localizations", {}).get("en", {}).get("stringUnit", {})
    return unit.get("value") or key


def missing_languages(key: str, entry: dict):
    """Languages with no real translation of `key`."""
    gaps = []
    for language in LANGUAGES:
        unit = entry.get("localizations", {}).get(language, {}).get("stringUnit", {})
        value = unit.get("value", "")
        if not value or value == english(key, entry):
            gaps.append(language)
    return gaps


def read_todo():
    if not TODO.exists():
        return {}
    match = re.search(r"```json\n(.*?)\n```", TODO.read_text(encoding="utf-8"), re.S)
    return json.loads(match.group(1)) if match else {}


def write_todo(gaps: dict, used: dict, catalogue: dict):
    lines = [
        "# iOS strings waiting for a Burmese or Mon translation",
        "",
        "Written by `python3 Scripts/check-strings.py --update-todo`; do not edit by hand.",
        "",
        "Each key below is shown in English to Burmese or Mon readers until a native speaker",
        "translates it. Nothing here was machine-translated, and nothing should be: add the",
        "translation to the translations sheet that `pnpm translate` reads",
        "(`shared/locales/sync.mjs`, ADR 0002), run it, then rerun this script with",
        "`--update-todo`. A Mon value identical to the English counts as untranslated,",
        "because that is what the sync writes when the sheet's Mon cell is empty.",
        "",
        "The check (`Scripts/check-strings.py`, run by `Scripts/swift-test.sh`) fails on a",
        "new untranslated key that is not listed here, and on a listed key that has since",
        "been translated or is no longer used.",
        "",
    ]
    for language in LANGUAGES:
        keys = sorted(k for k, langs in gaps.items() if language in langs)
        lines += [f"## Needs {NAMES[language]} ({language}): {len(keys)}", ""]
        for key in keys:
            where = ", ".join(sorted(used.get(key, [])))
            shown = json.dumps(english(key, catalogue[key]), ensure_ascii=False)
            lines.append(f"- {shown} ({where})")
        lines.append("")
    lines += [
        "## Machine-readable list",
        "",
        "```json",
        json.dumps({k: gaps[k] for k in sorted(gaps)}, ensure_ascii=False, indent=2),
        "```",
        "",
    ]
    TODO.write_text("\n".join(lines), encoding="utf-8")


def main(argv):
    catalogue = json.loads(CATALOGUE.read_text(encoding="utf-8"))["strings"]
    used, not_in_catalogue = {}, []
    for literal, files in sorted(extract().items()):
        key = resolve(literal, catalogue)
        if key is None:
            not_in_catalogue.append((unescape(literal), sorted(files)))
        else:
            used.setdefault(key, set()).update(files)

    gaps = {k: missing_languages(k, catalogue[k]) for k in used}
    gaps = {k: v for k, v in gaps.items() if v}

    if "--update-todo" in argv:
        write_todo(gaps, used, catalogue)
        print(f"check-strings: wrote {TODO.relative_to(IOS)} ({len(gaps)} keys).")

    listed = read_todo()
    unlisted = sorted(k for k in gaps if gaps[k] != listed.get(k))
    stale = sorted(k for k in listed if k not in gaps)

    failed = False
    if not_in_catalogue:
        failed = True
        print(f"check-strings: {len(not_in_catalogue)} key(s) are not in Localizable.xcstrings:", file=sys.stderr)
        for key, files in not_in_catalogue:
            print(f"  {json.dumps(key, ensure_ascii=False)}  ({', '.join(files)})", file=sys.stderr)
    if unlisted:
        failed = True
        print(f"check-strings: {len(unlisted)} untranslated key(s) are not in {TODO.name}:", file=sys.stderr)
        for key in unlisted:
            print(f"  {json.dumps(key, ensure_ascii=False)} needs {', '.join(gaps[key])}", file=sys.stderr)
    if stale:
        failed = True
        print(f"check-strings: {len(stale)} key(s) in {TODO.name} are translated or unused now:", file=sys.stderr)
        for key in stale:
            print(f"  {json.dumps(key, ensure_ascii=False)}", file=sys.stderr)
    if failed:
        print("check-strings: add missing keys to the translations sheet (or the catalogue, English only),",
              file=sys.stderr)
        print("  then run `python3 Scripts/check-strings.py --update-todo`.", file=sys.stderr)
        return 1

    counts = ", ".join(f"{sum(language in v for v in gaps.values())} need {NAMES[language]}" for language in LANGUAGES)
    print(f"check-strings: {len(used)} keys in use, all in the catalogue; {counts} (listed in {TODO.name}).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
