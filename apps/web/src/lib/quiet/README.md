# quiet-ds, vendored

A copy of the quiet-ds design system's `src/lib` (components, tokens, utilities, theme mechanism),
without its checks, fonts and strict mode. Source: github.com/janakhpon/quiet-ds, commit
6e8c218 (branch feat/2026-10-30/buttons-cards-menus).

quiet-ds is not published and its `dist` is not committed, so the web app cannot depend on it
directly. Do not edit these files here: change quiet-ds, then re-copy. The rules for using them
are in quiet-ds's DESIGN.md and AGENTS.md. App-specific CSS (the Mon-script fonts and the
compatibility classes) lives in src/app.css and src/lib/styles, never in this folder.
