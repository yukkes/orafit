# AGENTS.md

Orafit is a Java 17 JDBC compatibility layer for Oracle-oriented SQL on PostgreSQL.

## Non-negotiable rules

- Fail closed; never approximate unsupported Oracle semantics.
- Java owns JDBC/SQL behavior; `extension/src/` owns server-side runtime SQL.
- Parse with the exact JSqlParser version in `pom.xml`; parse once, rewrite once, render once.
- Preserve binds, JDBC metadata/errors/counts, OUT values, and deterministic DML state.
- Preserve pgJDBC `autosave=always` and `cleanupSavepoints=true` defaults.
- Oracle compatibility claims require Oracle 18c XE evidence.
- Do not edit generated files or vendor dependency JARs.
- Keep all tracked text LF.

## Workflow

- The standard Maven Wrapper is the build/test entry point. It downloads Maven and dependencies from Maven Central as needed. `act` and `.github/workflows/ci.sh` orchestrate local CI simulation.
- After Java changes: `./mvnw verify` runs the AOSP format check.
- Default DB tests start embedded Zonky PostgreSQL 17.10.
- CI compatibility evidence compares Oracle 18c XE with that embedded PostgreSQL 17.10; do not add a separate PostgreSQL container matrix.
- `./.github/workflows/ci.sh` runs only the Oracle 18c XE versus embedded PostgreSQL 17.10 differential through Docker/act and caches `act` when it is not installed.
- Manual `workflow_dispatch` runs may set `compatibility_status=unsupported`; that is a no-claim result, never a mismatch waiver.
- Do not add embedded-runtime-specific branches or skips to shared tests.

## Test contract

- `./mvnw test` runs the full local suite.
- `./mvnw verify` is the package gate.
- `ORAFIT_JDBC_URL` runs the same suite against an external PostgreSQL instance.
- Oracle/PostgreSQL exact-match logic stays in JUnit; declared unsupported cases must remain explicit `reject` contracts.
- Oracle differential tests run in CI; Maven verify covers native package generation and JAR contents.

## File placement

- Product code: `src/main/java/`
- Tests and evidence: `src/test/`
- Runtime SQL: `extension/src/`
- Third-party notices: `third-party/`
- Local CI runner: `.github/workflows/ci.sh`, `.actrc`
- CI orchestration: `.github/workflows/ci.yml`

## Publishing

- Publish only when explicitly requested.
- Use one atomic commit and non-force fast-forward updates.
- Never modify frozen `release-corpus.tsv`.

Prefer fewer mechanisms and one source of truth. Never weaken evidence to broaden support.
