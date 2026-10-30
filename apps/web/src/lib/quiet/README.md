# quiet-ds, vendored

A copy of the quiet-ds design system's `src/lib` (components, tokens, utilities, theme mechanism),
without its checks, fonts and strict mode. Source: github.com/janakhpon/quiet-ds, commit
0f511ed (branch feat/2026-10-30/header-labels).

quiet-ds is not published and its `dist` is not committed, so the web app cannot depend on it
directly. Do not edit these files here: change quiet-ds, then re-copy. The rules for using them
are in quiet-ds's DESIGN.md and AGENTS.md. App-specific CSS (the Mon-script fonts and the
compatibility classes) lives in src/app.css and src/lib/styles, never in this folder.

## Differences from quiet-ds

- `ThemeHead` is left out (its component and its export). It imports quiet-ds's own Inter files; the web app already loads Inter through @fontsource and sets the theme with `THEME_INIT_SCRIPT` from `theme.ts`, so those files are not copied.
