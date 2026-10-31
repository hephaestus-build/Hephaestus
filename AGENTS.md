# Hephaestus

All prose follows the [writing standard](docs/contributor/simplified-technical-english.md).
It has two profiles.
UI text, user-facing responses, and user docs use the product voice.
Admin docs, contributor docs, and repository instructions use STE.

Hephaestus is open-source software that gives software teams practice feedback: feedback on how developers apply engineering practices.
It checks developers' existing work in GitHub, GitLab, Slack, and Outline against the engineering practices that their project values.
A curated set of practices ships with it.
It delivers practice feedback on the work itself, on the developer's own practice pages, or in a conversation with Hephaestus.

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
The sandboxed runtime is an agent. Hephaestus, including its answers in a conversation, is not an agent.

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
- **Observability.** A new failure mode ships its signal, its alert or SLO, and its runbook row (`docs/contributor/observability-principles.md`).
- **Docs by audience.** Put each fact in the guide of the reader who acts on it.
  `docs/contributor/documentation-guide.md` defines the three guides, their page types, and their templates.
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

- `server/` — Spring Boot 4, Java 21, Spring Modulith 2, PostgreSQL with Liquibase. Guide: `server/AGENTS.md`.
- `webapp/` — React 19, TanStack Router/Query, Tailwind 4. Guides: `webapp/AGENTS.md` and `webapp/DESIGN.md`.
- `extension/` — the Chrome extension. Guide: `extension/AGENTS.md`.
- `docs/` — user, admin and contributor docs on GitHub Pages.
- `scripts/` — repository tooling in TypeScript.
  `tsconfig.node.json` type-checks it as one project with the agent runner, the practice script toolkit, and the bundled practice scripts.
  Task naming, lint configuration, and cross-platform commands: `docs/contributor/local-verification.mdx` § Repository tooling.

Skills live in `.claude/skills/<name>/`.
Codex reads only `.agents/skills/`, so the six contribution skills have byte-identical mirrors there.
`docs/contributor/ai-agent-workflow.mdx` § Slash commands lists each skill, what to say, what it does, and which skills it uses.

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

Suites: `vp run test:webapp`, `test:agents`, and `test:server:unit`.
`server/AGENTS.md` § Test tiers has the other server tiers.

## Pull requests

- Do not stage, commit, push, open a pull request, merge, release, or deploy without the maintainer's authorization.
  Use authorization already given for the current task.
  Do not request it again for each covered step.
  A request to plan or review does not authorize publication.
- Use Conventional Commit titles in plain language, with the types and scopes in `commitlint.config.ts`.
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
- After you push, `/watch-pr` watches the PR until it is ready to merge.

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
  Only the SPA, the extension, and the practice script toolkit have zod.
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

## Where to change what

| Change | Edit | Then run | Owning doc |
|---|---|---|---|
| HTTP endpoint or DTO | The controller | `vp run generate:api`. Commit the spec and both clients. | `server/AGENTS.md` § API and security conventions |
| Entity or column | The entity | `vp run db:draft-changelog`, which also refreshes the ERD | `docs/contributor/database-migration.mdx` |
| Configuration property | The properties class | `vp run generate:configuration-reference` | `docs/admin/configuration-reference.mdx` |
| Route | `webapp/src/routes/**` | `vp run dev:webapp` regenerates the route tree | `webapp/AGENTS.md` § Routing |
| Component | The component and its stories | `vp run test:webapp` and `test:webapp:stories` | `/storybook-components` |
| Workflow | `.github/workflows/*.yml` | `node scripts/check-workflows.ts` | `docs/contributor/ci-cd.mdx` |
| Agent guide or skill | `AGENTS.md` or `.claude/skills/` | `vp run gate:instructions` | `docs/contributor/ai-agent-workflow.mdx` |
| Docs page | `docs/**` | `vp run docs:build` and `gate:prose` | `docs/contributor/simplified-technical-english.md` |
| Screen that a user page shows | The route or component | `vp run generate:docs-screenshots`. Commit the images. | `docs/contributor/add-a-user-guide-screenshot.md` |
| Shipped behavior | A changeset too | `vp run gate:changesets` | `.changeset/README.md` |

Never hand-edit a generated file: the spec, `webapp/src/api/**`, `extension/src/api/**`, `routeTree.gen.ts`, the ERD, the configuration reference, or the user-guide screenshots.
`generate:api:client` empties both `src/api/` directories first.

A branch has one changelog, written by `db:draft-changelog`.
Never edit, rename, or delete a file under `db/changelog/` that reached `main`.
`master.xml` keeps its released include prefix fixed.
New migrations use timestamped XML files in `db/changelog/next/`, discovered by its one `includeAll`.
Wipe an older local database with `vp run dev:reset`.

## Command caveats

Each of these reports success and leaves a stale or wrong result.

- **`generate:api:specs` honours `HEPHAESTUS_APPLICATION_JAR`.**
  If it is set, the spec comes from that JAR, not from your checkout. Unset it after a CI-style run.
- **`-PpackagedServer=true`, one Gradle invocation per checkout, and `server/.env` in test JVMs** —
  `server/AGENTS.md` § Build traps.
