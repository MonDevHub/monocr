# Contributing to MonOCR

Welcome to the MonOCR Platform. As a polyglot monorepo, contributions must follow a specific workflow to maintain platform parity and architectural integrity.

## Workflow

The project uses a monorepo structure where each directory in `apps/` and `services/` is an independent application managed by a central orchestration layer at the root.

### Localization
Do not modify native resource files (Android XML, iOS Strings) directly.
1. Update the central translation source.
2. Run `pnpm translate` from the root to update all platforms.
3. Commit the changes across all affected platforms.

### Prerequisites

| | Version | Where it is pinned |
|---|---|---|
| Node | see `apps/web/.nvmrc` | CI reads the same file |
| pnpm | see `packageManager` in the root `package.json` | Corepack picks it up |
| Go | see `services/feedback/go.mod` | only needed for the feedback service |
| JDK / Xcode | **JetBrains** JDK 21 (the one inside Android Studio), Xcode 26.2+ | only needed for the mobile apps; the JDK vendor is pinned, not just the version |

### Development

```bash
pnpm install            # at the root only — this is one workspace
pnpm dev:all            # web + CLI + feedback service
```

No `.env` is needed to run the web app: it does OCR in the browser against a
model fetched from Hugging Face. `pnpm run dev` prints which service credentials
are missing and carries on. The feedback service and the localisation bridge do
need them — copy `.env.example` to `.env`.

### Before you open a pull request

```bash
pnpm --filter ./apps/web test           # unit tests
pnpm --filter ./apps/web run lint       # prettier + eslint
pnpm --filter ./apps/web run type-check # svelte-check
cd services/feedback && gofmt -l . && go vet ./... && go test ./...
```

CI runs all of the above, plus a gitleaks scan of the full history and a set of
cross-app invariants (the three bundled charsets must stay byte-identical, one
model revision everywhere, one architecture string, no accuracy figure that
traces to no run).

**CI tests the logic of both mobile apps but builds neither.** The `android` job
runs the unit tests (`./gradlew testDebugUnitTest` on JetBrains Runtime 21) but
does not build the app, and there are no instrumented tests. The `ios-core` job
runs `Scripts/swift-test.sh` over `MonOcrCore` only — 15 of the app target's 45
Swift files. If you touch either app, especially a decoder or a segmenter, run its tests
locally and say so in the PR. See
`docs/guides/mobile-build-and-test.md`.

### Segmentation and model changes: A/B first, ship only if better

A change that can alter the text MonOCR produces ships only with an A/B comparison, and only if
it is better. That covers a segmenter or its constants, tiling, preprocessing, decoding, and a new
model revision or export.

- **Two arms, same inputs.** A is the current code, B is the change. Nothing else differs between
  them: same images, same model revision, same settings.
- **Decide what counts as better before running.** Name the primary metric (usually CER, or for
  segmentation, lines found, missed and merged) and the guard metrics, and state the result that
  would ship B.
- **Ship only if B wins the primary metric and loses no guard metric.** A tie or an unclear result
  does not ship. If the change is still worth keeping, it goes behind an option that is off by
  default.
- **Put the result in the pull request:** the inputs, both arms' numbers, any per-bucket split (for
  example by line width), the exact command, and what the comparison doesn't cover.
- **Keep the shared fixtures passing.** The A/B decides whether the change is better; the fixtures
  under `shared/segmentation-fixtures/` check that every app still agrees. A change that alters a
  fixture's expected output regenerates it with its generator in `shared/segmentation-fixtures/`,
  never by hand, in the same pull request, and updates any constant `.github/workflows/ci.yml`
  asserts, with the A/B as the reason. The tiling fixture is generated from monocr-onnx's
  `tile_line`, so a tiling change lands there first.

### Claims in documentation and UI

Any number that reaches a README, a model card, or a user-facing string names
what it measures and where it can be traced to. A figure with no source is the
specific failure this project has already shipped: `97.5%+ accuracy` sat in two
shipped copy for months and traced to no run at all — one Android onboarding screen plus the Docs tab on both platforms. There is a CI job
that now fails on it.

## Coding Standards
- **Pragmatism Over Dogmatism**: We prefer simple, maintainable solutions over abstract over-engineering.
- **Independent Engines**: Ensure that changes to one app do not break the "Independent" nature of others. 
- **Staff-Grade Logic**: Every new feature must include proper error handling, structured logging, and defensive input validation.

### Commit Messages
We follow [Conventional Commits](https://www.conventionalcommits.org/):
- `feat(web)`: Adding a new web feature.
- `fix(android)`: Fixing an Android bug.
- `docs(adr)`: Adding a new Architecture Decision Record.
- `refactor(shared)`: Improving the locale sync script.
