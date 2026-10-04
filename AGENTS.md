# Hephaestus

All prose follows the [writing standard](docs/contributor/simplified-technical-english.md).
Apply it to docs, UI text, user-facing responses, comments, and repository instructions.

Hephaestus is an open-source AI mentor for software teams.
It checks developers' existing work in GitHub, GitLab, Slack, and Outline against the engineering practices that their project values.
A curated set of practices ships with it.
It delivers practice feedback on the work itself, on the developer's own practice pages, or in conversation with Heph.

## What we will not compromise on

1. **Feedback earns trust or it is not sent.** A review passes through occasion, evidence capture, observation, and delivery.
   Each stage can stop with its own recorded reason.
   Never turn a capture failure into a claim about someone's work.
   Never report withheld feedback as missing evidence.
   See `docs/contributor/practice-review-pipeline.mdx`.
2. **Workspaces cannot see each other.** SQL enforces tenancy.
   `DataIsolationArchitectureTest` asserts it.
   Every workspace-owned table carries the workspace.
   Every workspace endpoint is a `@WorkspaceScopedController`.
   See `docs/contributor/workspace-context.mdx`.
3. **Self-hosters get a release they can verify.** Releases have signed images, provenance, and SBOMs.
   The vulnerability policy is evaluated at build and release time.
   A released migration never changes.
   See `docs/contributor/release-management.mdx` and `docs/contributor/database-migration.mdx`.
4. **The artifact tested is the artifact shipped.** CI packages the server once.
   The OpenAPI spec, browser suite, and container image all come from that JAR.
   See `docs/contributor/ci-cd.mdx` § Build once.
5. **One vocabulary.** The product words below are the only words, in UI, code, docs and release
   notes alike.

## How we build

Build ambitious ideas with simple systems and software that feels obvious.
Do not preserve complexity because it already exists.
Do not add machinery because it looks architecturally impressive.
Find the real constraint.
Then choose the smallest model that makes the correct behavior unsurprising.

- Make the simplest maintainable change.
  Add no abstraction, configuration, or future-proofing that the current task does not need.
  Add no compatibility shim for a caller that does not exist.
- A framework or library feature over a hand-rolled one, every time it fits.
- Root causes over workarounds. A comment that documents a workaround is a flag that the fix is
  not done.
- Give each fact one home.
  A rule stated twice can cause a conflict.
  Make the second copy a pointer.
- Check before you change.
  Prevent scope creep.
  Honor the maintainer's intent with a minimal, realistic change.

The rest of this file gives defaults, not hard rules.
A maintainer's explicit instruction overrides any of it.
If a rule conflicts with the current task, state the conflict.
Get a decision instead of silently bypassing the rule.

## A small glossary

`docs/contributor/practice-feedback-language.md` owns the product vocabulary.
`docs/contributor/practice-review-glossary.mdx` owns the review operation's terms.
Define a term in one and cite it from the other.
Use these words on every task:

- **you** — the agent reading this file and changing Hephaestus.
- **we, maintainers** — the people building Hephaestus, who are talking to you now.
- **workspace** — one team's tenant: its connected repositories, members, practices and feedback.
  An **instance** is one running Hephaestus deployment hosting many workspaces.
- **practice** — a defined way of working used to review work.
  A **practice group** collects related practices.
  Never use *rule*, *detector*, or *category* for these concepts.
- **practice review** — one evidence-bounded operation: Hephaestus checks practices against one
  piece of reviewed work and may record observations.
- **observation** — one recorded result of reviewing one practice against one piece of reviewed
  work. Never *finding*, *detection*.
- **practice feedback**, shortened to **feedback** — guidance written from observations and addressed to a developer.
  It is uncountable.
  Write *3 pieces of feedback*, never *messages*.
- **channel** — where one piece of feedback is meant to appear.
- **delivery** — whether it was prepared, delivered, withheld, failed, or replaced.
- **reviewed work** — a pull request, merge request, issue, conversation or document under review.
  Say *pull request* or *merge request* when the provider is known.
- **developer** — the person an observation is about.
- **Heph** — the conversational assistant.
  Its product area is **mentor**.
  Neither Heph, the application, nor a review is an *agent*.
  That word means the sandboxed runtime that executes a review.
- **runtime role** — `server`, `worker` or `webhook`: the slice of one JAR a container boots.
- **integration** — one connected provider: GitHub, GitLab, Slack or Outline, each with its own
  adapter under `integration/`.

## Hit every surface

The most common defect is a change that works on the tested path but is missing elsewhere.
Before you report completion, check this list.
State which entries applied:

- **Integrations.** GitHub, GitLab, Slack and Outline each have an adapter. A provider-shaped
  feature needs a decision per adapter, even if the decision is "not supported here".
- **Runtime roles.** Gate a bean that exists in one role on that role.
  Its consumers must tolerate its absence (`server/AGENTS.md` § Things that bite).
  Production runs the webhook role in its own container.
- **Wire contract.** Anything crossing HTTP is a DTO in `server/openapi.yaml` and the generated clients.
  The clients live in `webapp/src/api/**` and `extension/src/api/**`.
  Change the controller.
  Regenerate the artifacts.
  Commit all three.
- **Schema.** An entity change needs a changelog and an ERD (`vp run db:draft-changelog`).
  A new workspace-owned table is workspace-scoped from its first migration.
- **Channels.** Feedback appears on the reviewed work, on the developer's practice page, and in conversation.
  A change to what feedback carries needs a decision per channel.
- **Reverse states.** Include needs exclude, pause needs resume, customize needs reset. A one-way
  door is a bug.
- **Both admin consoles.** Instance-wide and per-workspace administration share components.
  A scope-specific field in a shared component silently breaks the other console.
  See `webapp/AGENTS.md` § Which admin console a component belongs to.
- **UI states.** Every component ships stories for its empty, loading, and error states.
  The loading rules in `webapp/AGENTS.md` decide skeleton versus spinner.
- **Docs by audience.** `docs/user/` describes the shipped product in its own voice.
  It has no repository tooling or source paths.
  `docs/admin/` is for operators.
  `docs/contributor/` is for engineers.
  New vocabulary goes in the two glossaries.
- **Release note.** A change to shipped code ships a changeset in the operator's or user's voice —
  `.changeset/README.md`.

## How it works

One JAR boots in three runtime roles selected by `hephaestus.runtime.*`.
See ADR 0005, ADR 0008, and `docs/admin/runtime-roles.mdx`.
The webhook role receives provider events and publishes them to NATS JetStream.
The server consumes them, syncs the work, runs practice reviews in sandboxed containers, and delivers the resulting feedback.

The SPA talks to the server only through the generated client.
`docs/contributor/system-design.mdx` has the diagrams.
`docs/contributor/practice-review-pipeline.mdx` has the stages.

## Where code lives

- `server/` — Spring Boot 4, Java 21, and Spring Modulith 2.
  PostgreSQL uses Liquibase.
  `openapi.yaml` is generated.
  `server/AGENTS.md` has the build traps and entity conventions.
- `webapp/` — React 19 SPA, TanStack Router/Query, Tailwind 4, generated API client in `src/api/**`.
  `webapp/AGENTS.md` has the component and story conventions.
- `docs/` — user, admin and contributor docs published to GitHub Pages, including the generated ERD.
- `scripts/` — repository tooling in TypeScript.
  It uses the Node version pinned by `package.json#devEngines.runtime`.
  It runs through the Vite+ version pinned in `devDependencies`.
  `tsconfig.agents.json` type-checks these sources as one project:
  - The agent runner (`server/application/src/main/resources/agent/`).
  - The precompute runner and lib (`docker/agents/precompute/`).
  - The per-practice precompute scripts (`server/application/src/main/resources/practices/precompute/`).

Skills live in `.claude/skills/<name>/`.
Claude Code and opencode read them there.
Codex reads only `.agents/skills/`.
The four skills that drive a contribution have byte-identical mirrors there.
`gate:instructions` fails if a mirror differs.
Do not copy a skill elsewhere.

| Skill | When |
|-------|------|
| `/storybook-components` | Component props, stories, play functions, a11y posture. Grade a webapp diff. |
| `/composition-patterns` | Compound components, render props, React 19 API shape |
| `/web-design-guidelines` | UI accessibility and UX review |
| `/react-best-practices` | Frontend performance — a vendored Vercel pack. Much of it is Next.js-only. Read its applicability table first. |
| `/fix-ci`, `/land-pr`, `/resolve-review` | CI triage, opening a PR, answering review comments — mirrored for Codex |
| `/gh-stack` | Creating and maintaining stacked pull requests — mirrored for Codex |

## Dev servers

- `vp run dev` starts PostgreSQL, the server, and the webapp in one terminal.
  `dev:server` and `dev:webapp` are the halves.
  Host ports come from `server/.env`, with one set per worktree.
  Read them there instead of assuming defaults.
- `vp run dev:reset` wipes the local database.
  The data folder under `server/` is a bind mount.
  `docker compose down -v` leaves it in place.
- Stop what you started, by the PID you tracked. Other worktrees run their own servers on this host.
- `docs/contributor/local-development.mdx` has sign-in, bootstrap admins and the port map.

## Verifying

- Use the smallest proof that the change works.
  Run the test file you touched.
  Run the scoped check for the changed tree (`vp run check:affected` selects it from your diff).
- Test observable behavior.
  A story proves what a component renders from its props and installs no network.
  A route test owns the wire contract.
  Do not assert callback wiring or mirror the implementation.
- Backend behaviour changes ship with focused tests in the right tier, asserting on the rows they
  created (`server/AGENTS.md` § Test tiers).
- Before you push, run `vp run format`.
  Then run `vp run check`.
  The pre-push hook runs `check` again.
  `vp run verify` adds the credential-free builds and suites before review.
  CI owns images, browser suites, and live-service suites.
  See `docs/contributor/local-verification.mdx`.
- Ask before computer use or before you start a browser.
  Test against a run environment that the maintainer already started.

| Command | Does |
|---|---|
| `vp run format` / `format:check` | Apply / verify formatting (Java + TypeScript) |
| `vp run check` | Every gate in the `quality` group in `vite.config.ts`: static analysis, formatting, agent tests, repository policy |
| `vp run verify` | `check` plus the credential-free builds and test suites |
| `vp run test:webapp` | Vitest |
| `vp run test:agents` | Agent runtime and precompute specs, on Node |
| `vp run test:server:unit` | Server unit tests — the other tiers are in `server/AGENTS.md` § Test tiers |

### Task vocabulary

Task names are lowercase colon-separated words.
A word can also contain digits or hyphens.
The first word identifies the task type.
Only these prefixes are allowed:

| Prefix | Meaning |
|---|---|
| `affected` | Internal groups selected by `check:affected` for one changed tree |
| `build` | Produce a distributable artifact |
| `check` | Run an uncached read-only check or a group of checks. `check` is the local quality entry point. |
| `ci` | Internal groups shaped for CI jobs and runner platforms |
| `db` | Inspect or update the development database schema and its generated documentation |
| `dev` | Start, stop, reset, or configure local development infrastructure |
| `docs` | Build, serve, or lint the documentation site |
| `fix` | Apply formatting and safe lint fixes |
| `format` | Apply formatting. A final `check` segment makes the operation read-only. |
| `gate` | Produce one verdict that CI can annotate. Every gate belongs to `quality` unless explicitly CI-only in the task contract test. |
| `generate` | Regenerate a committed artifact from its authoritative source |
| `lint` | Run a linter. A final `fix` segment applies safe fixes. `report` writes a report. |
| `quality` | Internal graph with every local quality gate. Use `check` at the command line. |
| `release` | Prepare or publish a release version |
| `report` | Turn existing results into a human- or machine-readable report |
| `schema` | Refresh a checked-in external integration schema or contract fixture |
| `sync` | Reconcile checked-in values with an authoritative local source |
| `test` | Execute a test suite |
| `typecheck` | Run a language type checker without emitting artifacts |
| `verification` | Run credential-free builds and test suites beyond `quality`. The bare name is the internal graph. |
| `verify` | Run the complete credential-free local verification entry point |

Segments after the prefix identify the subject and specialization, such as
`test:server:integration`.
`:webapp`, `:server`, and `:agents` are the tree scopes.
`:java` scopes only `format` and `lint`.
The Java leg of `check` is `gate:server`.

### Lint and format

Oxlint lints.
Oxfmt formats, sorts imports, and sorts Tailwind classes.
One rule set has multiple layers.
The root `.oxlintrc.json` is the base for every TypeScript file and the config for the Node trees.
These trees are the agent runner, precompute, `scripts/**`, `load-tests/**`, and the task graph.
`oxlint.react.jsonc` is the React layer.

`oxlint.app.jsonc` adds the application compiler, Vitest, and Storybook policy for `webapp/.oxlintrc.json` and `extension/.oxlintrc.json`.
Docs extend the base and React layer.
Each tree holds only its additions or different decisions, with a reason for each.
Write a rule once.

`gate:docs-lint` type-checks the docs tree and runs `markdownlint-cli2` (`docs/.markdownlint-cli2.jsonc`).
`docs:lint` is its alias.

- **Run oxlint through `vp` from the repo root**, as CI does:
  - `vp -C webapp lint .`
  - `vp -C extension lint .`
  - `vp -C docs lint .`
  - `vp run gate:agents-lint`

  The `options` block (`typeAware`, `typeCheck`, `reportUnusedDisableDirectives`) travels through `extends`.
  Thus, a bare `oxlint` started inside a tree sees the same rules.
  It resolves `tsconfig.json` from where it started.
- **`extends` carries `categories`, `rules`, `plugins`, `overrides`, and `options`.**
  Each tree owns its `env`, `settings`, and `ignorePatterns`.
  Vite+ takes the config as an object and accepts only objects in `extends`.
  Thus, `webapp/tools/oxlint/load-config.ts` inlines the files for both Vite configs.
  It drops their `jsPlugins`, because that path rejects relative plugin paths.
  The file on disk keeps the paths for the CLI and editors.
- **Type-aware rules need a file named exactly `tsconfig.json`.**
  The root stub supplies one for the Node trees configured by `tsconfig.agents.json`.
  `load-tests/tsconfig.json` types the k6 scripts the same way.
- **The house rules are one oxlint plugin under `webapp/tools/oxlint/`.**
  All tree configs load it.
  Each config chooses which rules to turn on.
  Thus, adding a rule to the plugin enables it nowhere.
  `webapp/AGENTS.md` § Linting has the rest.
- **`vp run` does not give a command a POSIX shell on every platform.**
  Thus, a command never contains `$`.
  `scripts/ci-contract.test.ts` enforces this.
  When the config loads, `vite.config.ts` determines what a command reads from the environment.
  `scripts/check-runner-contract.ts` proves the runner facts that the graph needs.

  An oxfmt pattern with no `/` matches a basename at any depth.
  A pattern with a `/` is anchored at the working directory.
  Thus, a root-only pass pairs its patterns with `!*/**`.

## Pull requests

- Never stage, commit, push or open a pull request unless the maintainer asks. A branch is theirs
  to land.
- Use Conventional Commit titles in plain language, with the types and scopes in `CONTRIBUTING.md`.
  Example: `fix(webapp): feedback list no longer jumps while it loads`.
  Do not put `!` in the title.
  The changeset carries a breaking change.
- State the problem in one or two sentences in the body.
  Then explain the fix and verification.
  Use the shape in `.github/PULL_REQUEST_TEMPLATE.md`.
  Do not add model, agent, harness, or tool attribution to pull request titles or bodies.
- UI changes need before/after images.
  Motion or timing needs a short video.
  Never commit PR-only evidence.
  `/land-pr` owns its preparation and upload.
- A PR that changes shipped code ships a changeset.
  `.changeset/README.md` is the contract.
  With no TTY, hand-write `.changeset/<slug>.md` in the shape shown there.
  `verify-changesets` fails the PR without one and rejects `major` before 1.0.
  Write the summary as a release note.
  Use the operator's or user's voice.
  Never write it as a code description.
  Never add an agent-attribution trailer.
- Stacked PRs follow `CONTRIBUTING.md` § Stacked Pull Requests through `/gh-stack`. A layer that
  cannot satisfy the normal PR, release and quality requirements on its own is not a layer.
- When you monitor a PR, poll checks and comments newer than the last push.
  Verify each bot finding against the source.
  Fix the real ones.
  Dismiss the rest with a written reason.
  If nothing is new, stay quiet.
  When the bots are green on the latest commit, stop.

## Plans and work artifacts

Do not commit implementation plans, research notes, or scratch files.
Keep them only in the gitignored scratch directories named in `.gitignore`.
Record durable architecture, constraints, and decisions in their owning `docs/` document or an ADR under `docs/decisions/`.
When the product changes, update that document so the next reader finds facts instead of abandoned intentions.

A merged PR is the implementation record.
Do not keep a second checklist in the repository.
When you file an issue, follow `CONTRIBUTING.md` § Issues.
Every *Done when* bullet is a change that ships, never a measurement or a verdict alone.

## Taste

- Complexity belongs at the integration boundary.
  Adapters absorb a provider's shape.
  The review pipeline stays pure.
  Components stay presentational and take their data as props.
- Prefer inferred types to annotations.
  Do not use `any`.
  `no-explicit-any`, `no-non-null-assertion`, and the `no-unsafe-*` family are errors.
  A cast usually indicates that the upstream type is wrong.
  Use `satisfies`.
- Validate anything crossing a trust boundary: a webhook body, a hand-parsed stream, or a `JSON.parse`.
  Use a discriminated union, or a `zod` schema in the SPA.
  The SPA is the only tree that has zod.
  Never log a token, a secret, or a raw request body.
- A leading `_` marks what the language or a tool reads that way.
  Examples are an unused binding, a server field name, or a runtime global, never something private.
  Where evaluation order matters, separate import groups with blank lines.
  Oxfmt sorts within a group.
- Write repository automation in typed TypeScript.
  Keep shell only at a runtime boundary where Node is unavailable.
  Keep it POSIX-compatible.
- Comments state what the code cannot: a constraint, a platform behavior, or a rejected alternative.
  When the code moves, move its comments.
  Do not put a run number, measured duration, or incident in them.
  Deleting such a comment needs the same justification as adding one.
  Either the code now states the fact, or the fact moved to its one home.
- Our users read feedback about their own work. A wrong claim, a stale label or a lying spinner
  costs trust that a fast fix does not buy back.

## Generated artefacts

| Artefact | Command |
|---|---|
| `server/openapi.yaml` | `vp run generate:api:specs` |
| `webapp/src/api/**`, `extension/src/api/**` | `vp run generate:api:client` |
| `docs/contributor/erd/schema.mmd` | `vp run db:generate-erd-docs` |
| `webapp/src/routeTree.gen.ts` | TanStack Router Vite plugin |
| `server/generated-clients/build/generated/sources/**` | GraphQL and Outline codegen, owned by the generated-clients Gradle module |

Never hand-edit these.
`generate:api:client` empties both `src/api/` directories first.
Gradle-generated sources live under `build/` and are never committed.
Commit `server/openapi.yaml` and both generated clients with the API change that produced them.

## Database changes

Procedure: `docs/contributor/database-migration.mdx`.
Entity conventions that the drift gate reads: `server/AGENTS.md` § Schema changes.
`vp run db:draft-changelog` writes the drift into this branch's single changelog and wires it into `master.xml`.
A branch never hand-writes a changelog or adds a second one.
Never edit, rename, or delete a file under `db/changelog/` that reached `main`.
`master.xml` is append-only.

The migration procedure documents the verified v0.77.4 archival transition.
It does not permit future history rewrites.
Before `vp run dev`, follow the baseline runbook for existing developer databases or discard them with `vp run dev:reset`.

## Command caveats

Each of these reports success and leaves a stale or wrong result.

- **`generate:api:specs` honours `HEPHAESTUS_APPLICATION_JAR`.**
  If it is set, the script scrapes the spec from that JAR, not from your checkout.
  Unset it after a CI-style run.
  Without it, the script packages the server without tests.
  It boots the JAR under the `specs` profile with isolated HTTP ports.
- **`-PpackagedServer=true` consumes restored compiled classes.** It is reserved for CI artifact
  consumers.
  Local Gradle test tasks compile their inputs automatically.
  Never use packaged mode after you change source.
- **One Gradle invocation per checkout**, and `server/.env` can affect test JVMs — `server/AGENTS.md`
  § Build traps.
