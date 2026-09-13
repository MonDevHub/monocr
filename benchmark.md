# Benchmark — MonOCR UI/UX & Code Quality

An honest self-assessment, not a promotional one. Every rating below is grounded in what was
actually checked this session — real gate runs (`type-check`/`lint`/`vitest`/`build`), live
Playwright renders against the built preview server (not just "the build succeeded"), and 8
concurrent role-based audits (accessibility-engineer, frontend-engineer, ux-researcher,
product-manager, design-system-audit, ai-product-ux, senior-designer-loop, a GitHub-Primer
comparative lens) applied to `apps/web`, plus fresh audits of `apps/android` and `apps/ios`. Every
fix below passed through at least one independent adversarial verification pass before landing —
several were corrected *by* that pass, not just confirmed by it, and that's recorded honestly
below rather than smoothed over.

## 1. Level readiness, by dimension — `apps/web`, before this session → after

| Dimension | Before | After | Evidence |
| --- | --- | --- | --- |
| Icon rendering reliability | **Broken, live on production.** `.material-symbols-outlined` never set `font-family` — every icon rendered as its literal ligature name (`upload_file`, `progress_activity`, etc.) overlapping real content, confirmed on `ocr.mondevhub.com` | **Fixed at the root, then re-architected.** One-line CSS fix shipped first (`9becfc4`), then the whole ligature-font approach replaced with a self-hosted, zero-dependency inline-SVG `Icon.svelte` across all ~20 call sites (`1479e09`) | Independent Playwright render confirmed the glyph actually displays, not just that the build succeeds; a live verifier separately reproduced the fix via `getComputedStyle` before the architectural migration |
| Accessibility — modals | All 3 modals (`ConfirmationModal`, `SuccessModal`, `HistorySection`'s record viewer) had **dead** Escape-to-close — the handler lived on the backdrop, a sibling (not ancestor) of the focus-trapped content, so it could never fire once focus moved inside. None had `role="dialog"`/`aria-modal`/`aria-labelledby` | Fixed once, at the shared `focus-trap.ts` action (an `onEscape` callback), so every current and future modal inherits correct behavior from one place. All 3 now carry real dialog semantics | `46d3f7f`; independently verified — reader traced the actual event-bubbling path and confirmed the `id`/`aria-labelledby` pairs resolve correctly, no swapped handlers |
| Accessibility — contrast | Multiple real WCAG AA failures: `--fg-muted` itself was 3.33:1 before any opacity was even added on top; specific instances measured as low as 1.49–1.85:1 across `Footer.svelte` (global, every page), `docs/+page.svelte`, `contribute/+page.svelte`, `HistorySection.svelte`, and two form placeholders | All identified instances fixed, across **four verification rounds** — three of which found real remaining failures the prior round's sweep had missed | `c97b265`, `1ceabf0`, `8baba7f`, `0f2a909` — each contrast ratio recomputed from the actual hex/opacity values via the WCAG relative-luminance formula, not estimated |
| PWA installability | Manifest referenced two icon files (`android-chrome-192/512.png`) that **do not exist anywhere in the repo** — install-to-homescreen was broken or degraded. The shipping manifest config had also drifted from the correct (but dead, never-served) hand-authored version: wrong theme color, generic description, and literal, never-customized `["template", "starter", "sveltekit"]` categories | Icon paths corrected to files confirmed present at the exact declared dimensions; full manifest content (name, description, theme/background color, categories, screenshots) brought back in line with the correct source | `6714c59`; a follow-up verifier then caught the carried-over screenshot's dimensions were also wrong (1200×630 claimed, actual file is 1024×1024) — fixed in `51a49cb` |
| Design-system consistency — buttons | Stretched `w-full`/`flex-1` buttons scattered inconsistently across 4 sites, while `HistorySection`'s own confirm dialog already did the correct thing (`flex justify-end` + `min-w-[100px]`, content-sized). Separately: the destructive confirm button in that same dialog carried `bg-red-500` as a plain utility on top of `.btn-primary` and rendered near-black, not red — the danger signal on an irreversible action was silently lost to a Tailwind-layer cascade issue | Every `btn-primary`/`btn-secondary` in the app now follows the content-sized precedent; a real `.btn-danger` modifier (defined after `.btn-primary` in the same layer, so it wins) replaces the losing utility classes; the modal footer's narrow-screen full-width fallback was restored alongside it | `837e942`, `751f24d`; visually re-verified via Playwright screenshot, and the danger-button fix confirmed by both compiled-CSS byte order and live computed `background-color` |
| Content accuracy | `**GDPR**`/`**CCPA**` rendered as literal asterisks on `/privacy` (flagged by an earlier audit, never actually fixed until now); footer showed a hardcoded `"Version 0.2.0"` against a real package version of `0.4.0` — two minors stale | Both fixed; the version now reads from the same `__APP_VERSION__` build-time define `Header.svelte` already used correctly | `7645113`, `21a93fe`; the asterisk fix specifically re-confirmed live against the dev server, not just in source |
| Content scope (per direct request) | Language dropdown showed a "Vulnerable Language" subtitle under Mon; docs page had a hardcoded "Core Contributors" section (3 names, roles, external profile links) | Both removed; orphaned i18n keys and the now-unused `m` import cleaned up rather than left dead, paraglide output regenerated to match | `6ba745d`, `d976d06` |
| Dead weight | 3 fully-dead components (`Button`, `Breadcrumb`, `Dropzone` — exported, never imported, already drifted from what real call sites did instead); 2 dead Google Fonts requests (Material Symbols, Public Sans — the latter's token applied nowhere); a dead `.animate-spin-slow` keyframe | All removed | `1479e09` |

## 2. Verification discipline this session

- Every commit above passed `type-check` (0 errors), `lint` (prettier + eslint), `vitest`
  (172/172), and `build` before landing — not just once at the end, after each meaningful change.
- **7 independent adversarial verification passes** were dispatched against this session's own
  work. **4 of them found something real the producing pass had missed**: a global,
  worse-than-already-fixed contrast failure in `Footer.svelte`; 6 more unfixed instances of the
  same pattern still in `docs/+page.svelte`; 2 placeholder-text contrast failures the greps for
  plain `opacity-NN` classes didn't catch; a false historical claim in a commit message
  ("an existing asymmetry" that git history showed never existed); and — while checking the
  button-sizing fix — a pre-existing, previously-unnoticed bug where the destructive confirm
  button rendered near-black instead of red. All were corrected, and each correction is recorded
  in the commit history rather than folded away silently.
- The icon migration and the button-consistency fix were each independently re-verified via a
  **live Playwright render against the built preview server** — not just "the build succeeded,"
  but an actual screenshot and a check for zero remaining `fonts.googleapis.com` requests, zero
  console errors, and real SVG glyphs in the DOM.

## 3. Known, explicit gaps — surfaced this session, not yet acted on

- **Critical, `apps/web`:** the OCR engine already computes a capture-quality/confidence signal
  (`assessCapture` in `monocr-onnx.ts`) and discards it at `console.warn` — never reaches the UI.
  A wrong transcription is presented with the same confidence as a correct one.
- Duplicate `<SEO>` tags currently ship on `/docs`, `/report`, and `/contribute` (two `<title>`s,
  two canonical links, two OG/JSON-LD blocks per page).
- Form accessibility: `/contribute`'s textarea has no real accessible name; `/report`'s wraps an
  *empty* `<label>` (looks fixed in source, isn't); both forms fail silently on submit error.
- Service-worker mid-scan-reload risk: `skipWaiting`+`clientsClaim` can force every open tab to
  reload, including one mid-PDF-job, with no checkpointing — needs a design decision (debounce vs.
  user-prompted update vs. job checkpointing), not a unilateral fix.
- Typography/spacing consolidation: 85 raw arbitrary `text-[Npx]` values vs. 42 standard Tailwind
  scale classes vs. 31 token-based values, per the design-system audit's own count — a root-cause
  fix (a real type scale, lint-enforced) rather than 85 individual edits.
- Dependency updates flagged, not applied (each needs a compatibility pass, not a blind bump):
  TypeScript (5→7, two majors), `pdfjs-dist` (4→6, two majors, this app does client-side PDF
  parsing), Vite/`@sveltejs/vite-plugin-svelte`/vitest-browser (paired major bumps that must move
  together).
- Mon-language translation lags Burmese badly (33/113 keys untranslated vs. 3/113), and the
  entire `/privacy` page is English-only prose despite being the page that substantiates the
  on-device privacy claim.

## 4. Fresh audits this session — `apps/android`, `apps/ios` (no fixes applied yet)

**Android — 2 Blocking, several High:** the exact strings that warn about a blurry/unreliable
scan are untranslated in both Mon and Burmese; the entire onboarding screen (`IntroScreen.kt`) is
hardcoded English with zero `stringResource` calls. Beyond those: a systemic contrast pattern
(alpha-reduced secondary text failing as low as 1.92:1, ~50 call sites, one root-cause token fix);
5 icon-only touch targets shrunk below the 48dp minimum, including the primary feedback-submit
button; multi-page PDF scans never surface their own quality warnings (`_rerunnableImage` is
explicitly nulled on the PDF path, which is what gates the one UI that shows them).

**iOS — 1 Blocking, several High:** the capture-quality warning — the one signal proving this app
doesn't silently return confident nonsense — is correctly wired end-to-end but renders at
**2.12:1** contrast in light mode (needs 4.5:1), for exactly the users it exists to protect.
Beyond that: VoiceOver users cannot reach the delete button on any history row at all
(`.accessibilityElement(children: .combine)` swallows it); the in-app language switcher silently
doesn't apply to 5 of 6 sheet-presented screens (a SwiftUI environment-modifier-ordering trap);
zero Dynamic Type support anywhere in the app, including the OCR result text itself.

## 5. Last verified

**2026-09-13.** 16 commits on `fix/2026-09-13/audit-response-round1` (branched from `main`), plus
this file, spanning the icon-rendering root-cause fix, the full SVG icon migration, modal
accessibility, four rounds of contrast fixes, two rounds of PWA manifest fixes, content-scope
removals, content-accuracy fixes, a button-consistency pass, and a destructive-button color fix —
each independently verified per Section 2 above. Section 4's Android/iOS findings are audit-only;
nothing has been changed on either platform yet.
