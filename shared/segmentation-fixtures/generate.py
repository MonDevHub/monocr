"""Generate the shared tiling fixture from the canonical Python implementation.

The expected tile widths in this file are produced by running
monocr_onnx.segmenter.tile_line, never by hand. Four ports (web TS, Android
Kotlin, iOS Swift, Rust) assert against the output, so an authored number here
would silently become the contract.

Run: MONOCR_ONNX_PYTHON=/path/to/monocr-onnx/python uv run --with pillow \
     --with numpy --with opencv-python-headless python3 generate.py tiling-cases.json

Add --check to compare against the committed file instead of writing it, and exit 1
on any difference. That mode exists because the fixture spent time asserting its own
provenance was `scratchpad/gen_tiling_fixtures.py` — a script that does not exist and
was never committed — while this file, sitting beside it, reproduced it exactly. The
instruction "regenerate" was unfollowable as written, and nothing would have said so.

MONOCR_ONNX_PYTHON is the `python` directory of a monocr-onnx checkout. Leave it
unset if monocr_onnx is already importable in the environment.
"""

import importlib.util
import json
import os
import sys
from pathlib import Path

import numpy as np
from PIL import Image

TARGET_H = 160
TARGET_W = 1024


def load_segmenter():
    """Import the reference implementation, or say exactly what is missing.

    monocr-onnx is a separate repository, so its location cannot be derived from
    this file. A failure here means the fixture would be generated from nothing,
    which is worse than not generating it.

    From a checkout, segmenter.py is loaded straight off disk rather than as
    `monocr_onnx.segmenter`. The package's __init__ pulls in onnxruntime and
    pdf2image; tile_line and cut_column need only cv2, numpy and Pillow, and
    requiring a runtime wheel to emit a JSON file of integers is not a trade
    worth making.
    """
    checkout = os.environ.get("MONOCR_ONNX_PYTHON")
    if checkout:
        path = Path(checkout).expanduser().resolve() / "monocr_onnx" / "segmenter.py"
        if not path.is_file():
            raise SystemExit(
                f"MONOCR_ONNX_PYTHON={checkout} has no monocr_onnx/segmenter.py; "
                "point it at the `python` directory of a monocr-onnx checkout"
            )
        spec = importlib.util.spec_from_file_location("monocr_onnx_segmenter", path)
        if spec is None or spec.loader is None:
            raise SystemExit(f"could not load {path} as a Python module")
        module = importlib.util.module_from_spec(spec)
        try:
            spec.loader.exec_module(module)
        except ImportError as exc:
            raise SystemExit(
                f"{path} could not be imported ({exc}). It needs numpy, Pillow and "
                "opencv-python-headless."
            ) from exc
    else:
        try:
            from monocr_onnx import segmenter as module
        except ImportError as exc:
            raise SystemExit(
                f"cannot import monocr_onnx.segmenter ({exc}). Set MONOCR_ONNX_PYTHON "
                "to the `python` directory of a monocr-onnx checkout, or install the "
                "package."
            ) from exc

    try:
        return module.cut_column, module.tile_line
    except AttributeError as exc:
        raise SystemExit(
            f"the reference segmenter has no {exc.name}; this generator is out of date "
            "with monocr-onnx"
        ) from exc


# Ink patterns expressed as a portable rule so every port can build the same
# image without shipping binaries. "mod_eq m" = ink where x % m == 0.
PATTERNS = {
    "mod_eq": lambda m: (lambda x: x % m == 0),
    "mod_ne": lambda m: (lambda x: x % m != 0),
    "solid": lambda _: (lambda x: True),
    "blank": lambda _: (lambda x: False),
}

CASES = [
    # The six the web suite already pins. Keeping them identical means the
    # shared file cannot silently change web's expectations.
    ("fits", 200, 100, "mod_eq", 7),
    ("no gap in the search window", 3000, 100, "mod_ne", 137),
    ("solid ink", 2500, 120, "solid", 0),
    ("a gap inside the search window", 3000, 100, "mod_ne", 23),
    ("sparse ink", 2200, 90, "mod_eq", 11),
    ("exactly at the boundary", 640, 100, "mod_eq", 3),
    # Added coverage: the boundary either side, a very wide line, a short line
    # that scales up, a blank line, and a single-pixel-tall degenerate input.
    ("one pixel over the boundary", 641, 100, "mod_eq", 3),
    ("one pixel under the boundary", 639, 100, "mod_eq", 3),
    ("very wide book line", 6000, 110, "mod_ne", 31),
    ("short line scales up", 900, 40, "mod_eq", 5),
    ("tall line scales down", 4000, 400, "mod_ne", 17),
    ("blank line", 2000, 100, "blank", 0),
    ("one pixel tall", 2000, 1, "mod_eq", 9),
    ("narrow and tall", 50, 300, "mod_eq", 3),
]


def build(w: int, h: int, kind: str, m: int) -> Image.Image:
    inked = PATTERNS[kind](m)
    arr = np.full((h, w), 255, dtype=np.uint8)
    cols = [x for x in range(w) if inked(x)]
    if cols:
        arr[:, cols] = 0
    # No `mode=` argument: Pillow removes it in 13 (2026-10-15), and a uint8 2-D
    # array already infers "L". Verified byte-identical across all 14 cases here
    # before it was dropped, so the checked-in fixture does not move.
    return Image.fromarray(arr)


def build_fixture(cut_column, tile_line) -> dict:
    """The whole artifact, as data. Separated from writing it so --check can compare
    without touching the file it is checking."""

    out = {
        "_comment": (
            "Generated by shared/segmentation-fixtures/generate.py from "
            "monocr_onnx.segmenter.tile_line. Do not hand-edit expected values; "
            "regenerate, and verify with --check. Consumed by web, Android, iOS and "
            "Rust tiling tests, and by mon_OCR tests/test_tiling_fixture.py."
        ),
        "target_height": TARGET_H,
        "target_width": TARGET_W,
        "cut_search_fraction": 0.12,
        "cut_ink_threshold": 250,
        "cases": [],
    }

    for name, w, h, kind, m in CASES:
        crop = build(w, h, kind, m)
        tiles = tile_line(crop, TARGET_H, TARGET_W)
        widths = [t.size[0] for t in tiles]

        # Partition property, asserted here so a bad generator cannot emit a
        # fixture that every port then faithfully reproduces.
        assert sum(widths) == w, f"{name}: tiles sum {sum(widths)} != width {w}"
        assert all(x > 0 for x in widths), f"{name}: non-positive tile width"

        out["cases"].append(
            {
                "name": name,
                "width": w,
                "height": h,
                "ink": {"kind": kind, "modulus": m},
                "expected_tile_widths": widths,
            }
        )

    # A couple of direct cut_column probes, so a port can localise a failure to
    # the cut search rather than the tiling loop.
    probes = []
    for name, w, h, kind, m, x0, ideal in [
        ("gap available", 3000, 100, "mod_ne", 23, 0, 640),
        ("no gap available", 3000, 100, "solid", 0, 0, 640),
        ("ideal at the end", 800, 100, "mod_eq", 7, 0, 800),
    ]:
        crop = build(w, h, kind, m)
        probes.append(
            {
                "name": name,
                "width": w,
                "height": h,
                "ink": {"kind": kind, "modulus": m},
                "x0": x0,
                "ideal": ideal,
                "expected_cut": int(cut_column(crop, x0, ideal, w)),
            }
        )
    out["cut_column_probes"] = probes
    return out


def render(fixture: dict) -> str:
    return json.dumps(fixture, indent=2, ensure_ascii=False) + "\n"


def main() -> int:
    argv = [a for a in sys.argv[1:] if a != "--check"]
    check = "--check" in sys.argv[1:]
    if len(argv) != 1:
        raise SystemExit("usage: generate.py [--check] <destination.json>")

    dest = Path(argv[0])

    # Existence first: load_segmenter() raises SystemExit on a checkout without
    # monocr-onnx, which would pre-empt the message written for exactly this case.
    if check and not dest.is_file():
        print(f"FAIL {dest} does not exist; run without --check to write it")
        return 1

    text = render(build_fixture(*load_segmenter()))

    if check:
        if dest.read_text(encoding="utf-8") != text:
            print(f"FAIL {dest} does not match what this generator produces")
            print("     Regenerate and commit it in the same change, or the four ports")
            print("     stay pinned to expectations nobody can reproduce.")
            return 1
        fixture = json.loads(text)
        print(f"ok   {dest.name} matches — {len(fixture['cases'])} cases, "
              f"{len(fixture['cut_column_probes'])} probes")
        return 0

    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(text, encoding="utf-8")
    fixture = json.loads(text)
    print(f"wrote {dest} — {len(fixture['cases'])} cases, "
          f"{len(fixture['cut_column_probes'])} probes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
