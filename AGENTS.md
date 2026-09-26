# Alertify Agent Guide

These instructions apply to the entire repository. Keep machine-specific paths,
credentials, editor settings, approval rules, and optional local tooling in an
untracked `AGENTS.override.md`, not in this file.

## Working principles

- Inspect the relevant code and configuration before proposing or making a
  change. Preserve unrelated user changes.
- Follow the formatting and patterns already used near the code being changed.
- Prefer the smallest relevant validation first, then broaden validation when a
  change crosses module or service boundaries.
- Do not commit generated build output, runtime state, local certificates,
  credentials, tokens, or temporary test scripts.
- Do not assume that a tool or service exists unless it is declared by tracked
  project files.

## Repository layout

- `backend/` is a Java 25 multi-module Maven project:
  - `worker-contract/` contains shared worker contracts.
  - `worker-runtime/` contains worker execution infrastructure.
  - `alert-templates/` contains Alert and Procedure templates.
  - `core/` contains the backend API, persistence, scheduling, and orchestration.
  - `worker-standard/` and `worker-playwright/` are worker applications.
- `frontend/` is an Angular application.
- `database/migrations/` contains ordered PostgreSQL migrations.
- `grpc/`, `identity/`, and `publisher/` contain infrastructure configuration.
- `runner/`, `run.bat`, and `run.sh` configure and start the local stack.
- `compose.yaml` defines the local multi-container application.

## Toolchains and Docker

- Docker is the expected prerequisite for development, builds, and integration
  checks. If it is unavailable, recommend installing Docker Engine or Docker
  Desktop, as appropriate for the host, before attempting project validation.
- Prefer disposable Docker containers over host-installed Java, Maven, Node.js,
  npm, or Playwright. Use host tools only when containers are impractical or the
  task explicitly concerns the host environment.
- The backend requires Java 25. Use a Maven image compatible with Java 25, such
  as `maven:3.9-eclipse-temurin-25`.
- The frontend requires Node.js `>=22.22.3`; the tracked environment template
  uses Node 24.
- Keep the Playwright Maven dependency, npm package, and Docker image on the same
  version. The currently tracked version is `1.62.0`.
- This project uses Jackson 3. Use the `tools.jackson.*` packages rather than
  Jackson 2's `com.fasterxml.jackson.*` packages.

## Backend validation

- Run Maven from `backend/` and include `clean` when validating generated gRPC
  and Protobuf sources.
- For a focused module, use `mvn clean -pl <module> -am test`. For a backend-wide
  change, use `mvn clean test`.
- On hosts where a Docker bind mount does not provide a native Linux filesystem,
  especially Windows hosts, do not run Protobuf-generating Maven goals directly
  against the bind mount. Copy `backend/` into a disposable Maven container,
  run Maven on the container filesystem, and discard the container afterward.
  A portable validation sequence is:

  1. `docker run --rm -d --name alertify-maven-validation --entrypoint tail -w /workspace maven:3.9-eclipse-temurin-25 -f /dev/null`
  2. `docker cp backend/. alertify-maven-validation:/workspace`
  3. `docker exec alertify-maven-validation mvn clean test`
  4. `docker stop alertify-maven-validation`

- Keep source edits on the host. Container build output is validation-only.

## Frontend and runner validation

- Run `npm ci` before frontend validation and `npm run build` for a production
  build. Use a disposable Node 24 container when practical.
- The frontend currently has no tracked test script; do not claim frontend tests
  ran unless a test command actually exists and was executed.
- Validate runner changes with `node --test runner/run.test.js`, preferably in a
  disposable Node 24 container.
- For ad hoc Node.js scripts with installed dependencies, place the script under
  the same temporary package tree as `node_modules`; ESM bare imports resolve
  from the importing script's directory and its ancestors.

## Running the local stack

- Use `./run.sh` on POSIX hosts or `.\run.bat` on Windows hosts.
- For automation, always pass `--non-interactive` and explicit options. Invoking
  a runner without options opens an interactive checklist.
- Use `--configure-only` to reconcile `.env` and inspect the plan without
  starting services.
- The supported reuse flags are `--skip-keycloak`, `--skip-redis`,
  `--skip-database`, `--skip-backend`, `--skip-frontend`, `--skip-publisher`,
  `--skip-worker-standard`, and `--skip-worker-playwright`.
- A skipped component must already be running and usable. Never skip a component
  affected by the current change. Backend and worker skips are independent.
- When Docker cleanup is appropriate, use the explicit, quoted
  `--cleanup-docker-preserve-images=PATTERNS` option. Matching images are kept;
  other unused images and build cache may be removed. For example:
  `"--cleanup-docker-preserve-images=maven:*;node:*;mcr.microsoft.com/playwright:*;monitoring-*"`.
- Run the full affected stack after changes to shared context-path behavior,
  backend configuration, identity configuration, database or Redis setup,
  worker code, gRPC contracts, or certificates.

## Code conventions

- Keep Java method declarations with a return type, including `void`, on one
  line. This does not apply to constructors, type declarations, annotations,
  method calls, or control-flow statements.
- Keep every invocation whose receiver is `LOGGER` or `eventLogger` entirely on
  one line, including all arguments.
- When a Java control-flow statement omits braces and has one statement, leave a
  blank line before the next unrelated statement.
- Preserve public API and backend/worker contract compatibility unless the task
  explicitly requires a coordinated contract change.
- Add or update focused tests alongside behavior changes.

## Database migrations

- Migration names must follow `<number>.<description>.sql` and use the next
  available number.
- Never edit a migration that may already have been applied. The migration
  runner records SHA-256 checksums and rejects changed files. Add a new migration
  instead.
- Keep schema changes and the application code that depends on them compatible
  during startup and rollout.

## Secrets and sensitive data

- Treat `.env` as sensitive. Use `.env.template` to understand configuration,
  and never print or commit generated values from `.env`.
- Never place passwords, bearer tokens, authorization headers, secret parameter
  values, encryption keys, or private certificate keys in source, commands,
  logs, screenshots, persisted diagnostics, or agent output.
- Preserve encryption and redaction boundaries when changing Alerts,
  Procedures, worker messages, status payloads, audit events, imports, exports,
  and writable parameter handling.
- Do not serialize secret-derived values into status messages or diagnostic
  maps. Sanitize HTTP and browser errors because they may contain request
  headers.
- Only public CA certificates may be exported for local trust. Never export,
  mount into application containers, or commit a CA private key.

## Browser and authenticated checks

- Use a separate disposable official Playwright container for ad hoc browser
  checks; do not repurpose application worker containers.
- Pin the Playwright image and package to the same version. Run ad hoc containers
  with `--rm`, `--init`, and `--ipc=host`, and mount the repository read-only
  unless the check must create a project artifact.
- Read application credentials from environment variables at runtime. Use
  `APP_ADMIN_USERNAME` and `APP_ADMIN_PASSWORD` for application administration,
  not identity-server administration credentials.
- Authenticate through the frontend's Authorization Code flow with PKCE. Do not
  enable a direct password grant as a testing shortcut.
- Keep access tokens only in process memory. Never print or persist them.
- Preserve the configured public URL and `APP_CONTEXT_PATH` during browser
  checks. Origins used for CORS and WebSocket handshakes do not include the path.
- A `202 Accepted` execution response is asynchronous. Poll the corresponding
  execution until its terminal state or `finishedAt` is present before reporting
  the result.
- When a check is intended to validate TLS trust, install the exported public CA
  in the disposable browser environment. Do not bypass validation with
  `ignoreHTTPSErrors`.

## Completion report

- State what changed, which validations ran, and their outcomes.
- Distinguish source-level checks, container builds, and live stack or browser
  verification. Do not imply that a broader check ran when it did not.
- Call out any validation that could not run and the concrete reason.
