# Repository Guidelines

## Project Structure & Module Organization

- `backend/src/main/java/com/genvn/`: Java 21/Spring Boot runtime, organized into `api`, `game`, `story`, `narrative`, `speculation`, `asset`, `llm`, `prompt`, and `persistence` packages.
- `backend/src/test/java/com/genvn/`: backend tests; `support/` contains deterministic model, dice, and image doubles.
- `frontend/src/`: React/TypeScript application, shared hooks, CSS, and `components/`. Frontend regressions live in `frontend/tests/`.
- `backend/data/sessions/` and `backend/data/assets/`: generated saves and per-session artwork; keep these out of version control. `docs/` holds technical documentation.

## Build, Test, and Development Commands

Use Java 21+ and Node 22.12+ (Node 20.19+ within 20.x is also supported). Run these from the repository root:

```bash
./run-backend.sh                         # Bootstrap local config; serve on 8080
./run-frontend.sh                        # Install missing dependencies; serve on 5180
(cd backend && ./gradlew test bootJar)   # Backend tests and executable JAR
(cd frontend && npm ci)                  # Install locked frontend dependencies
(cd frontend && node --test tests/*.test.mjs)
(cd frontend && npm run build)           # TypeScript checks and Vite production build
```

For one backend class, use `./gradlew test --tests 'com.genvn.DiceTest'` from `backend/`. Packaging scripts live in each module's `tools/` directory.

## Coding Style & Naming Conventions

Follow existing formatting: four-space Java indentation; two-space TypeScript/TSX indentation, double quotes, and semicolons. Use PascalCase for classes/components, camelCase for functions and variables, and `use*` for hooks. Keep player-facing copy in Chinese. TypeScript uses strict checking; no formatter or linter is configured.

## Testing Guidelines

Backend tests use JUnit 5; API tests use standalone MockMvc. Name classes `*Test.java`. Frontend tests use Node's `node:test` and the TSX component harness; name files `*.test.mjs`. Add behavior-focused regressions for fixes, including failure and concurrency paths when relevant. No numeric coverage threshold is configured. Run both suites and the frontend build before submitting; use mocks and temporary data instead of paid APIs or personal saves.

## Commit & Pull Request Guidelines

History uses concise conventional subjects such as `fix(dice): ...` and `chore: ...`; follow that style. PRs should explain the problem, resulting behavior, and validation performed. Link relevant issues, include screenshots for UI changes, and update README documentation when behavior or configuration changes.

## Architecture & Configuration Safeguards

The model proposes narrative changes; the runtime validates state and owns dice outcomes. Preserve speculative-branch isolation. Never commit or print `backend/config/application.yml` credentials; update `application.example.yml`, property defaults, and `ConfigFileTest` together for new backend settings. Preserve unrelated working-tree edits and existing saves.
