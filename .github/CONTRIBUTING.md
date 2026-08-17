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
| JDK / Xcode | JDK 21, current Xcode | only needed for the mobile apps |

### Development

```bash
pnpm install            # at the root only — this is one workspace
pnpm dev:all            # web + feedback service
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

**CI does not build or test Android or iOS.** There is no Gradle wrapper
committed and iOS needs a macOS runner. If you touch either app — especially a
decoder or a segmenter — run its tests locally and say so in the PR.

### Claims in documentation and UI

Any number that reaches a README, a model card, or a user-facing string names
what it measures and where it can be traced to. A figure with no source is the
specific failure this project has already shipped: `97.5%+ accuracy` sat in two
onboarding screens for months and traced to no run at all. There is a CI job
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
