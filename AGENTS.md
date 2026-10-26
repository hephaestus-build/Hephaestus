# Hephaestus

All prose follows the [writing standard](docs/contributor/simplified-technical-english.md).
It has two profiles.
UI text, user-facing responses, and user docs use the product voice.
Admin docs, contributor docs, and repository instructions use STE.

Hephaestus is open-source software that gives software teams practice feedback: feedback on how developers apply engineering practices.
It checks developers' existing work in GitHub, GitLab, Slack, and Outline against the engineering practices that their project values.
A curated set of practices ships with it.
It delivers practice feedback on the work itself, on the developer's own practice pages, or in conversation with Heph.

## What we will not compromise on

1. **Feedback earns trust or it is not sent.** A review passes through selection, evidence capture, result admission, and delivery.
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
5. **One vocabulary.** Use the terms from [Product vocabulary](#product-vocabulary)
   for product concepts in UI, code, docs and release notes.

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

## Product vocabulary

[Practice feedback language](docs/contributor/practice-feedback-language.md) owns product terms.
[Practice review glossary](docs/contributor/practice-review-glossary.mdx) owns review and runtime terms.
Use their exact names in prose, code, HTTP and release notes.
Define each term in one owning page and cite it elsewhere.
The sandboxed runtime is an agent. Heph and the application are not agents.

In this file, **you** is the agent that reads it and changes Hephaestus.
**We**, the maintainers, are the people who build Hephaestus and talk to you now.

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
- **Destinations.** Feedback appears on the reviewed work, on the developer's practice page, and in conversation.
  A change to what feedback carries needs a decision per destination.
- **Reverse states.** Include needs exclude, pause needs resume, customize needs reset. A change
  that cannot be undone is a bug.
- **Both admin consoles.** Instance-wide and per-workspace administration share components.
  A scope-specific field in a shared component silently breaks the other console.
  See `webapp/AGENTS.md` § Which admin console a component belongs to.
- **UI states.** Every component ships stories for its empty, loading, and error states.
  The loading rules in `webapp/DESIGN.md` decide skeleton versus spinner.
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

## Where code lives

- `server/` — Spring Boot 4, Java 21, Spring Modulith 2, PostgreSQL with Liquibase.
  `server/AGENTS.md` has the build traps and entity conventions.
- `webapp/` — React 19 SPA, TanStack Router/Query, Tailwind 4.
  `webapp/AGENTS.md` has the component conventions and `webapp/DESIGN.md` the surface rules.
- `docs/` — user, admin and contributor docs published to GitHub Pages.
- `scripts/` — repository tooling in typed TypeScript, run through the pinned Vite+ and Node.
  `tsconfig.agents.json` also type-checks the agent runner and the practice script toolkit and scripts.

Skills live in `.claude/skills/<name>/`.
Codex reads only `.agents/skills/`, so the four contribution skills have byte-identical mirrors there.
`docs/contributor/ai-agent-workflow.mdx` § Slash commands has the rules.

| Skill | When |
|-------|------|
| `/storybook-components` | Component props and stories. Grade a webapp diff. |
| `/composition-patterns` | Compound components and React 19 API shape |
| `/web-design-guidelines` | UI accessibility and UX review |
| `/react-best-practices` | Frontend performance. Read its applicability table first. |
| `/fix-ci`, `/land-pr`, `/resolve-review`, `/gh-stack` | CI triage, opening a PR, review comments, stacked PRs |

## Dev servers

- `vp run dev` starts PostgreSQL, the server, and the webapp. `dev:server` and `dev:webapp` are the halves.
  Read the host ports from `server/.env`: each worktree has its own set.
- `vp run dev:reset` wipes the local database.
- Stop what you started, by the PID you tracked. Other worktrees run their own servers on this host.
- `docs/contributor/local-development.mdx` has sign-in, bootstrap admins and the port map.

## Verifying

- Use the smallest proof that the change works.
  Run the test file you touched, then `vp run check:affected`.
- Test observable behavior.
  A story proves what a component renders from its props and installs no network.
  A route test owns the wire contract.
  Do not assert callback wiring or mirror the implementation.
- Backend behavior changes ship with focused tests in the right tier, asserting on the rows they
  created (`server/AGENTS.md` § Test tiers).
- Before you push, run `vp run format`, then `vp run check`.
  `vp run verify` adds the credential-free builds and suites.
  CI owns images, browser suites, and live-service suites (`docs/contributor/local-verification.mdx`).
- Ask before computer use or before you start a browser.
  Test against a run environment that the maintainer already started.

| Command | Does |
|---|---|
| `vp run format` / `format:check` | Apply / verify formatting (Java + TypeScript) |
| `vp run check` | Every gate in the `quality` group in `vite.config.ts` |
| `vp run verify` | `check` plus the credential-free builds and test suites |
| `vp run test:webapp` / `test:agents` / `test:server:unit` | Vitest / agent runtime and practice scripts / server unit tests |

## Pull requests

- Do not stage, commit, push, open a pull request, merge, release, or deploy without the maintainer's authorization.
  Use authorization already given for the current task.
  Do not request it again for each covered step.
  A request to plan or review does not authorize publication.
- Use Conventional Commit titles in plain language, with the types and scopes in `CONTRIBUTING.md`.
  Example: `fix(webapp): feedback list no longer jumps while it loads`.
  Do not put `!` in the title: the changeset carries a breaking change.
- The body states the problem in one or two sentences, then the fix and verification,
  in the shape of `.github/PULL_REQUEST_TEMPLATE.md`.
  Do not add model, agent, harness, or tool attribution to titles, bodies, or commits.
- UI changes need before/after images, and motion needs a short video.
  `/land-pr` owns them. Never commit PR-only evidence.
- A PR that changes shipped code ships a changeset, written as a release note in the operator's or user's voice.
  `.changeset/README.md` is the contract.
- Stacked PRs follow `CONTRIBUTING.md` § Stacked Pull Requests through `/gh-stack`. A layer that
  cannot satisfy the normal PR, release and quality requirements on its own is not a layer.
- When you monitor a PR, poll checks and comments newer than the last push.
  Verify each bot comment against the source.
  Fix the real ones.
  Dismiss the rest with a written reason.
  If nothing is new, stay quiet.
  When the bots are green on the latest commit, stop.

## Plans and work artifacts

Do not commit implementation plans, research notes, or scratch files.
Keep them in the gitignored scratch directories named in `.gitignore`.
Record durable decisions in their owning `docs/` document or an ADR under `docs/decisions/`.
A merged PR is the implementation record.
Issues follow `CONTRIBUTING.md` § Issues.

## Taste

- Complexity belongs at the integration boundary.
  Adapters absorb a provider's shape.
  The review pipeline stays pure.
  Components stay presentational and take their data as props.
- Prefer inferred types to annotations. Do not use `any`.
  A cast usually indicates that the upstream type is wrong. Use `satisfies`.
- Validate anything crossing a trust boundary: a webhook body, a hand-parsed stream, or a `JSON.parse`.
  Use a discriminated union, or a `zod` schema where the tree has zod.
  Never log a token, a secret, or a raw request body.
- A leading `_` marks only what the language or a tool reads that way, never something private.
  Separate import groups with blank lines where evaluation order matters. Oxfmt sorts within a group.
- Write repository automation in typed TypeScript.
  Keep POSIX shell only at a runtime boundary where Node is unavailable.
- Comments state what the code cannot: a constraint, a platform behavior, or a rejected alternative.
  When the code moves, move its comments.
  Do not put a run number, measured duration, or incident in them.
  Deleting such a comment needs the same justification as adding one.
- Our users read feedback about their own work. A wrong claim, a stale label or a lying spinner
  costs trust that a fast fix does not buy back.

## Generated artifacts

| Artifact | Command |
|---|---|
| `server/openapi.yaml` | `vp run generate:api:specs` |
| `docs/admin/configuration-reference.mdx` | `vp run generate:configuration-reference` |
| `webapp/src/api/**`, `extension/src/api/**` | `vp run generate:api:client` |
| `docs/contributor/erd/schema.mmd` | `vp run db:generate-erd-docs` |
| `webapp/src/routeTree.gen.ts` | TanStack Router Vite plugin |
| `server/generated-clients/build/**` | Gradle codegen for GraphQL and Outline, never committed |

Never hand-edit these.
`generate:api:client` empties both `src/api/` directories first.
Commit `server/openapi.yaml` and both generated clients with the API change that produced them.

## Database changes

Procedure: `docs/contributor/database-migration.mdx`.
Entity conventions that the drift gate reads: `server/AGENTS.md` § Schema changes.
`vp run db:draft-changelog` writes the drift into this branch's single changelog and wires it into `master.xml`.
A branch never hand-writes a changelog or adds a second one.
Never edit, rename, or delete a file under `db/changelog/` that reached `main`.
`master.xml` is append-only.

Bring an older local database to the baseline (`docs/admin/liquibase-baseline-runbook.md`) or wipe it with `vp run dev:reset`.

## Command caveats

Each of these reports success and leaves a stale or wrong result.

- **`generate:api:specs` honours `HEPHAESTUS_APPLICATION_JAR`.**
  If it is set, the script scrapes the spec from that JAR, not from your checkout.
  Unset it after a CI-style run.
  Without it, the script packages the server without tests.
  It boots the JAR under the `specs` profile with isolated HTTP ports.
- **`-PpackagedServer=true`, one Gradle invocation per checkout, and `server/.env` in test JVMs** —
  `server/AGENTS.md` § Build traps.
