# MonOCR Web

The browser app at [ocr.mondevhub.com](https://ocr.mondevhub.com). It reads printed Mon text from
JPEG, PNG, WebP and PDF files inside the tab.

The model, its accuracy figures and the project's limitations are in the
[root README](../../README.md).

## How it runs

ONNX Runtime Web runs the model on WebGPU when the browser offers it, and on a single-threaded
Wasm backend otherwise. The model is fetched once and kept in the Cache API. No image and no
recognised text leaves the machine: there is no network call on the recognition path. Sending a
correction or a contribution is opt-in.

Imports are capped at 50 MiB (`CONFIG.UI.MAX_IMAGE_SIZE_MB`). For larger files or whole folders,
use [`monocr-cli`](../cli/README.md), which runs the same model and takes PDFs up to 500 MiB. The
`pip install monocr` on the app's docs page is a different project,
[`janakhpon/monocr`](https://github.com/janakhpon/monocr): same model, its own segmentation, so
page-level output will not match this app line for line.

```
Image bytes (Uint8Array)
  createImageBitmap      -> decode, flatten transparency onto white -> ImageData
  normalizePagePolarity  -> invert ONCE per page if the background is dark
  segmentLines           -> blur, adaptive threshold, suppressPageRules,
                            smear, projection profile -> LineSegment[]
  assessCapture          -> capture-quality warnings; drops nothing
  tileLine               -> split lines too wide for the window -> LineSegment[]
  processLine            -> crop + letterbox to 160x1024 + normalize [-1.0, 1.0]
  session.run            -> ONNX Runtime Web (monocr.onnx), [1, 1, 160, 1024]
  decodePredictions      -> greedy CTC decode -> string
```

All of this runs in `ocr.worker.ts`, off the main thread.

- **Printed rules.** `suppressPageRules` runs inside `segmentLines`, after binarisation and
  before the smear, which would otherwise widen a rule into something no line kernel matches. An
  unbroken run of ink spanning at least half the page is a rule (`RULE_SPAN = 0.5`, 15px floor).
  If clearing rules would remove more than 80% of the ink (`RULE_MAX_INK_SHARE = 0.8`), it has
  found text, and the mask is left untouched.
- **Polarity** is decided once per page, before segmenting, because the projection profile
  treats dark pixels as ink.
- **No background levelling, on purpose.** The mobile ports divide out a background estimate to
  flatten sepia paper and grey panels; web does not. `segmentation.ts` records why: it is the
  expensive half, and it is not idempotent.
- **Joining.** Tiles of one line join with no separator; distinct lines join with a newline.

The engine is four flat files in `src/lib/`: `segmentation.ts`, `monocr-onnx.ts`,
`ocr.worker.ts` and `capture-quality.ts`. Tests sit beside the file they cover as `*.test.ts`.

## Develop

Requires Node.js 22 (`.nvmrc`) and pnpm 10 (`packageManager` in the root `package.json`).

```bash
pnpm install        # from the repository root
cd apps/web
pnpm dev            # copies the ONNX Runtime Wasm and pdf.js worker, then starts Vite
```

Optional: keep the model on disk, so a reload does not pull 46.2 MB from Hugging Face.

```bash
curl -L -o static/monocr.onnx \
  https://huggingface.co/janakhpon/monocr/resolve/d3d9d5e/onnx/monocr.onnx
```

`static/monocr.onnx` is gitignored. In development `src/lib/config.ts` uses it when present and
falls back to the pinned URL otherwise, printing which one it chose; production never reads it.
Fetch exactly that revision and keep it equal to `CONFIG.MODELS.RECOGNITION`: `static/charset.txt`
is 276 characters, and the app refuses a model that does not match it with a `ModelContractError`
at load rather than returning wrong text.

## Test and check

```bash
pnpm test             # Vitest
pnpm run type-check   # svelte-check
pnpm run lint         # prettier --check and eslint
pnpm build            # production build
```

CI runs all four on every push, and a separate step fails unless at least 172 tests passed.

## Deploy

Cloudflare Pages (`@sveltejs/adapter-cloudflare`), with `apps/web` as the root directory. The model
is not bundled, being far past the edge asset limit: production fetches it from Hugging Face at the
pinned revision.

## Licence

MIT
