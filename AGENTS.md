# Hephaestus

Hephaestus is an open-source AI mentor for software teams. It reads the work developers already do
in GitHub, GitLab, Slack and Outline against the engineering practices their project cares about — a
curated set of practices ships with it — and delivers practice feedback on the work itself, on the
developer's own practice pages, or in conversation with Heph.

## What we will not compromise on

1. **Feedback earns trust or it is not sent.** A review passes through occasion, evidence capture,
   observation and delivery, and each stage may stop with its own recorded reason. A capture failure
   is never turned into a claim about someone's work, and a withheld piece of feedback is never
   reported as missing evidence — `docs/contributor/practice-review-pipeline.mdx`.
2. **Workspaces cannot see each other.** Tenancy is enforced in SQL and asserted by
   `DataIsolationArchitectureTest`; every workspace-owned table carries the workspace and every
   workspace endpoint is a `@WorkspaceScopedController` — `docs/contributor/workspace-context.mdx`.
3. **Self-hosters get a release they can verify.** Signed images, provenance, SBOMs and a
   vulnerability policy evaluated at build and release time; a released migration never changes —
   `docs/contributor/release-management.mdx`, `docs/contributor/database-migration.mdx`.
4. **The artifact tested is the artifact shipped.** CI packages the server once, and the OpenAPI
   spec, the browser suite and the container image all come from that JAR —
   `docs/contributor/ci-cd.mdx` § Build once.
5. **One vocabulary.** The product words below are the only words, in UI, code, docs and release
   notes alike.

## How we build

Ambitious ideas, simple systems, software that feels obvious. Do not preserve complexity because it
already exists, and do not add machinery because it looks architecturally impressive. Find the real
constraint, then fight for the smallest model that makes the correct behaviour unsurprising.

- The simplest maintainable change. No abstraction, configuration or future-proofing the task in
  front of you does not need; no compatibility shim for a caller that does not exist.
- A framework or library feature over a hand-rolled one, every time it fits.
- Root causes over workarounds. A comment that documents a workaround is a flag that the fix is
  not done.
- One home per fact. A rule stated twice is a conflict waiting to happen; the second copy is a
  pointer.
- Measure twice, cut once, and fight scope creep: honour the maintainer's intent minimally and
  realistically.

The rest of this file is good defaults, not hard rules. A maintainer's explicit instruction
overrides any of it; if a rule here fights the task in front of you, say so and get a decision
rather than working around it silently.

## A small glossary

`docs/contributor/practice-feedback-language.md` owns the product vocabulary and
`docs/contributor/practice-review-glossary.mdx` owns the review operation's terms; a term is defined
in one and cited from the other. The words you need on every task:

- **you** — the agent reading this file and changing Hephaestus.
- **we, maintainers** — the people building Hephaestus, who are talking to you now.
- **workspace** — one team's tenant: its connected repositories, members, practices and feedback.
  An **instance** is one running Hephaestus deployment hosting many workspaces.
- **practice** — a defined way of working used to review work; a **practice group** collects
  related practices. Never *rule*, *detector*, *category*.
- **practice review** — one evidence-bounded operation: Hephaestus checks practices against one
  piece of reviewed work and may record observations.
- **observation** — one recorded result of reviewing one practice against one piece of reviewed
  work. Never *finding*, *detection*.
- **practice feedback**, shortened to **feedback** — guidance written from observations and
  addressed to a developer; uncountable, so write *3 pieces of feedback*, never *messages*.
- **channel** and **delivery** — where one piece of feedback is meant to appear, and whether it was
  prepared, delivered, withheld, failed or replaced.
- **reviewed work** — a pull request, merge request, issue or conversation under review. Say
  *pull request* or *merge request* when the provider is known.
- **developer** — the person an observation is about.
- **Heph** — the conversational assistant; **mentor** is its product area. Neither Heph, the
  application nor a review is an *agent*; that word means the sandboxed runtime that executes a
  review.
- **runtime role** — `server`, `worker` or `webhook`: the slice of one JAR a container boots.
- **integration** — one connected provider: GitHub, GitLab, Slack or Outline, each with its own
  adapter under `integration/`.

## Hit every surface

The most common defect here is a change that works on the path you tested and is missing everywhere
else. Before calling a change done, walk this list and say which entries applied:

- **Integrations.** GitHub, GitLab, Slack and Outline each have an adapter. A provider-shaped
  feature needs a decision per adapter, even if the decision is "not supported here".
- **Runtime roles.** A bean that exists in one role is gated on that role and its consumers
  tolerate its absence (`server/AGENTS.md` § Things that bite); production runs the webhook role in
  its own container.
- **Wire contract.** Anything crossing HTTP is a DTO in `server/openapi.yaml` and the generated
  client in `webapp/src/api/**`; change the controller, regenerate both, commit both.
- **Schema.** An entity change is a changelog and an ERD (`vp run db:draft-changelog`), and a
  new workspace-owned table is workspace-scoped from its first migration.
- **Channels.** Feedback appears on the reviewed work, on the developer's practice page and in
  conversation; a change to what feedback carries needs a decision per channel.
- **Reverse states.** Include needs exclude, pause needs resume, customize needs reset. A one-way
  door is a bug.
- **Both admin consoles.** Instance-wide and per-workspace administration share components; a
  scope-specific field in a shared one breaks the other console silently
  (`webapp/AGENTS.md` § Which admin console a component belongs to).
- **UI states.** Every component ships stories for its empty, loading and error states; the
  loading rules in `webapp/AGENTS.md` decide skeleton versus spinner.
- **Docs by audience.** `docs/user/` is the shipped product in its own voice, with no repo tooling
  or source paths; `docs/admin/` is for operators; `docs/contributor/` is for engineers. New
  vocabulary lands in the two glossaries.
- **Release note.** A change to shipped code ships a changeset in the operator's or user's voice —
  `.changeset/README.md`.

## How it works

One JAR boots in three runtime roles selected by `hephaestus.runtime.*` (ADR 0005, ADR 0008;
`docs/admin/runtime-roles.mdx`). The webhook role receives provider events and publishes them to
NATS JetStream; the server consumes them, syncs the work, runs practice reviews in sandboxed
containers, and delivers the resulting feedback. The SPA talks to the server only through the
generated client. `docs/contributor/system-design.mdx` has the diagrams and
`docs/contributor/practice-review-pipeline.mdx` the stages.

## Where code lives

- `server/` — Spring Boot 4, Java 21, Spring Modulith 2; Liquibase-managed PostgreSQL; generated
  `openapi.yaml`. `server/AGENTS.md` has the build traps and entity conventions.
- `webapp/` — React 19 SPA, TanStack Router/Query, Tailwind 4, generated API client in `src/api/**`.
  `webapp/AGENTS.md` has the component and story conventions.
- `docs/` — user, admin and contributor docs published to GitHub Pages, including the generated ERD.
- `scripts/` — repository tooling in TypeScript, on the Node version pinned by
  `package.json#devEngines.runtime`, run through the Vite+ version pinned in `devDependencies`. The
  agent runner (`server/application/src/main/resources/agent/`), the precompute runner and lib
  (`docker/agents/precompute/`) and the per-practice precompute scripts
  (`server/application/src/main/resources/practices/precompute/`) are type-checked as one project
  via `tsconfig.agents.json`.

Skills live in `.claude/skills/<name>/`, read by Claude Code and opencode; Codex reads
`.agents/skills/` and nothing else, so the four that drive a contribution are mirrored there byte
for byte and `gate:instructions` fails when a half drifts. Copy a skill nowhere else.

| Skill | When |
|-------|------|
| `/storybook-components` | Component props, stories, play functions, a11y posture; grading a webapp diff |
| `/composition-patterns` | Compound components, render props, React 19 API shape |
| `/web-design-guidelines` | UI accessibility and UX review |
| `/react-best-practices` | Frontend performance — a vendored Vercel pack; read its applicability table first, since much of it is Next.js-only |
| `/fix-ci`, `/land-pr`, `/resolve-review` | CI triage, opening a PR, answering review comments — mirrored for Codex |
| `/gh-stack` | Creating and maintaining stacked pull requests — mirrored for Codex |

## Dev servers

- `vp run dev` starts PostgreSQL, the server and the webapp in one terminal; `dev:server` and
  `dev:webapp` are the halves. Host ports come from `server/.env`, one set per worktree, so read
  them there rather than assuming the defaults.
- `vp run dev:reset` wipes the local database; the data folder under `server/` is a bind mount
  that `docker compose down -v` leaves in place.
- Stop what you started, by the PID you tracked. Other worktrees run their own servers on this host.
- `docs/contributor/local-development.mdx` has sign-in, bootstrap admins and the port map.

## Verifying

- Smallest proof that the change works: the test file you touched, the scoped check for the tree
  you changed (`vp run check:affected` selects it from your diff).
- Test observable behaviour. A story proves what a component renders from its props and installs
  no network; a route test owns the wire contract. Do not assert callback wiring or mirror the
  implementation.
- Backend behaviour changes ship with focused tests in the right tier, asserting on the rows they
  created (`server/AGENTS.md` § Test tiers).
- Before pushing, `vp run format` then `vp run check`; the pre-push hook runs `check` again.
  `vp run verify` adds the credential-free builds and suites before review. CI owns images,
  browser and live-service suites — `docs/contributor/local-verification.mdx`.
- Ask before computer use or spinning up a browser; a run environment the maintainer already
  started is the one to test against.

| Command | Does |
|---|---|
| `vp run format` / `format:check` | Apply / verify formatting (Java + TypeScript) |
| `vp run check` | Every gate in the `quality` group in `vite.config.ts`: static analysis, formatting, agent tests, repository policy |
| `vp run verify` | `check` plus the credential-free builds and test suites |
| `vp run test:webapp` | Vitest |
| `vp run test:agents` | Agent runtime and precompute specs, on Node |
| `vp run test:server:unit` | Server unit tests — the other tiers are in `server/AGENTS.md` § Test tiers |

### Task vocabulary

Task names are lowercase colon-separated words; a word may also contain digits or hyphens. The
first word says what kind of task it is, and only these prefixes are allowed:

| Prefix | Meaning |
|---|---|
| `affected` | Internal groups selected by `check:affected` for one changed tree |
| `build` | Produce a distributable artifact |
| `check` | Run an uncached read-only check or a group of checks; `check` is the local quality entry point |
| `ci` | Internal groups shaped for CI jobs and runner platforms |
| `db` | Inspect or update the development database schema and its generated documentation |
| `dev` | Start, stop, reset, or configure local development infrastructure |
| `docs` | Build, serve, or lint the documentation site |
| `fix` | Apply formatting and safe lint fixes |
| `format` | Apply formatting; a final `check` segment makes the operation read-only |
| `gate` | Produce one verdict that CI can annotate; every gate belongs to `quality` unless explicitly CI-only in the task contract test |
| `generate` | Regenerate a committed artifact from its authoritative source |
| `lint` | Run a linter; a final `fix` segment applies safe fixes and `report` writes a report |
| `quality` | Internal graph containing every local quality gate; use `check` at the command line |
| `release` | Prepare or publish a release version |
| `report` | Turn existing results into a human- or machine-readable report |
| `schema` | Refresh a checked-in external integration schema or contract fixture |
| `sync` | Reconcile checked-in values with an authoritative local source |
| `test` | Execute a test suite |
| `typecheck` | Run a language type checker without emitting artifacts |
| `verification` | Run credential-free builds and test suites beyond `quality`; the bare name is the internal graph |
| `verify` | Run the complete credential-free local verification entry point |

Segments after the prefix identify the subject and specialization, such as
`test:server:integration`. `:webapp`, `:server` and `:agents` are the tree scopes; `:java` scopes
only `format` and `lint`, and the Java leg of `check` is `gate:server`.

### Lint and format

Oxlint lints; oxfmt formats and sorts imports. Each tree states its rule set in full —
`webapp/.oxlintrc.json`, `docs/.oxlintrc.json`, and the root `.oxlintrc.json` for the agent trees,
`scripts/**` and tooling config — and each config carries the reasoning for its own deltas.
`gate:docs-lint` type-checks the docs tree and runs `markdownlint-cli2` (`docs/.markdownlint-cli2.jsonc`);
`docs:lint` is its alias.

- **Start every oxlint run from the repo root.** A nested config *replaces* the root's rules rather
  than merging, and `options` — `typeAware`, `reportUnusedDisableDirectives` — is honoured only from
  the config oxlint discovers as the root. Started inside `webapp/`, every type-aware rule reads as
  enabled and checks nothing.
- **Type-aware rules need a file named exactly `tsconfig.json`.** The root stub exists so the Node
  trees, configured by `tsconfig.agents.json`, have one.
- **The house rules are one oxlint plugin under `webapp/tools/oxlint/`.** All three configs load it
  and each chooses which rules to turn on, so adding a rule there enables it nowhere.
  `webapp/AGENTS.md` § Linting has the rest.
- **`vp run` does not give a command a POSIX shell on every platform.** So a command never contains
  `$`, which `scripts/ci-contract.test.ts` enforces, and what it reads from the environment is
  decided in `vite.config.ts` when the config loads. The runner facts the graph relies on are proven
  in `scripts/check-runner-contract.ts`. An oxfmt pattern with no `/` matches a basename at any
  depth and one with a `/` is anchored at the working directory, so a root-only pass pairs its
  patterns with `!*/**`.

## Pull requests

- Never stage, commit, push or open a pull request unless the maintainer asks. A branch is theirs
  to land.
- Conventional Commit titles in plain language, with the types and scopes in `CONTRIBUTING.md`:
  `fix(webapp): feedback list no longer jumps while it loads`. No `!` in the title; a breaking
  change is carried by the changeset.
- Body: the problem in a sentence or two, then how you fixed it and how you verified it.
  `.github/PULL_REQUEST_TEMPLATE.md` is the shape. Do not add model, agent, harness or tool
  attribution to pull request titles or bodies.
- UI changes need before/after images; motion or timing needs a short video. Never commit PR-only
  evidence; `/land-pr` owns its preparation and upload.
- A PR that changes shipped code ships a changeset — `.changeset/README.md` is the contract. With no
  TTY, hand-write `.changeset/<slug>.md` in the shape shown there; `verify-changesets` fails the PR
  without one and rejects `major` before 1.0. The summary is a release note in the operator's or
  user's voice, never a code description, and never carries an agent-attribution trailer.
- Stacked PRs follow `CONTRIBUTING.md` § Stacked Pull Requests through `/gh-stack`. A layer that
  cannot satisfy the normal PR, release and quality requirements on its own is not a layer.
- When babysitting: poll checks and comments newer than the last push, verify each bot finding
  against the source, fix the real ones, dismiss the rest with a written reason. Stay quiet when
  nothing is new; stop when the bots are green on the latest commit.

## Plans and work artifacts

Do not commit implementation plans, research notes or scratch files; the gitignored scratch
directories the `.gitignore` names are the only place for them. Durable architecture, constraints
and decisions go into the `docs/` document that owns them, or an ADR under `docs/decisions/`, and
get updated when the product changes so the next reader finds facts rather than abandoned
intentions. A merged PR is the implementation record; do not keep a second checklist in the
repository. An issue you file follows `CONTRIBUTING.md` § Issues: every *Done when* bullet is a
change that ships, never a measurement or a verdict alone.

## Taste

- Complexity belongs at the integration boundary. Adapters absorb a provider's shape; the review
  pipeline stays pure; components stay presentational and take their data as props.
- Inferred types over annotations. `any` is the enemy: `no-explicit-any`, `no-non-null-assertion`
  and the `no-unsafe-*` family are errors, and a cast is usually the linter telling you the type is
  wrong upstream — reach for `satisfies`.
- Validate anything crossing a trust boundary — a webhook body, a hand-parsed stream, a
  `JSON.parse` — with a discriminated union, or with a `zod` schema in the SPA, the only tree that
  has zod. Never log a token, a secret or a raw request body.
- A leading `_` marks what the language or a tool reads that way — an unused binding, a server
  field name, a runtime global — never something private. Import groups are separated by blank
  lines where their evaluation order matters; oxfmt sorts within a group.
- Repository automation is typed TypeScript; shell stays only at a runtime boundary where Node is
  unavailable, kept POSIX-compatible.
- Comments say what the code cannot — a constraint, a platform behaviour, a rejected alternative —
  and move when the code moves. Nothing in them is a run number, a measured duration or an
  incident. Deleting one of these needs the same justification as adding one: either the code now
  says it, or it moved to its one home.
- Our users read feedback about their own work. A wrong claim, a stale label or a lying spinner
  costs trust that a fast fix does not buy back.

## Generated artefacts

| Artefact | Command |
|---|---|
| `server/openapi.yaml` | `vp run generate:api:specs` |
| `webapp/src/api/**` | `vp run generate:api:client` |
| `docs/contributor/erd/schema.mmd` | `vp run db:generate-erd-docs` |
| `webapp/src/routeTree.gen.ts` | TanStack Router Vite plugin |
| `server/generated-clients/build/generated/sources/**` | GraphQL and Outline codegen, owned by the generated-clients Gradle module |

Never hand-edit these. `generate:api:client` empties `webapp/src/api/` first;
Gradle-generated sources live under `build/` and are never committed. Commit `server/openapi.yaml`
and `webapp/src/api/**` with the API change that produced them.

## Database changes

Procedure: `docs/contributor/database-migration.mdx`. Entity conventions the drift gate reads:
`server/AGENTS.md` § Schema changes. `vp run db:draft-changelog` writes the drift into this
branch's single changelog and wires it into `master.xml`; a branch never hand-writes one or adds a
second. A file under `db/changelog/` that reached `main` is never edited, renamed or deleted, and
`master.xml` is append-only. The verified v0.77.4 archival transition is documented in the migration
procedure; it is not permission for future history rewrites. Existing developer databases must follow
the baseline runbook or be discarded with `vp run dev:reset` before `vp run dev`.

## Command caveats

Each of these reports success and leaves a stale or wrong result.

- **`generate:api:specs` honours `HEPHAESTUS_APPLICATION_JAR`.** With it set, the
  spec is scraped from that JAR, not from your checkout. Unset it after a CI-style run. Without it
  the script packages the server without running tests and boots the JAR under the `specs` profile
  with isolated HTTP ports.
- **`-PpackagedServer=true` consumes restored compiled classes.** It is reserved for CI artifact
  consumers. Local Gradle test tasks compile their inputs automatically; never use packaged mode
  after changing source.
- **One Gradle invocation per checkout**, and `server/.env` can affect test JVMs — `server/AGENTS.md`
  § Build traps.
