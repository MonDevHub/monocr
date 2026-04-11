# Contributing to MonOCR

Welcome to the MonOCR Platform. As a polyglot monorepo, contributions must follow a specific workflow to maintain platform parity and architectural integrity.

## Workflow

The project uses a monorepo structure where each directory in `apps/` and `services/` is an independent application managed by a central orchestration layer at the root.

### Localization
Do not modify native resource files (Android XML, iOS Strings) directly.
1. Update the central translation source.
2. Run `pnpm translate` from the root to update all platforms.
3. Commit the changes across all affected platforms.

### Development
- Run `pnpm dev:all` to start the frontend and backend services.
- Use `pnpm install` at the root only.

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
