# ADR 0015: Unified integration framework — package layout and SPI surface

**Status:** Accepted (amended 2026-05-27 for Phase 1-4 restructure; 2026-09-17 — see the update below)
**Date:** 2026-05-26
**Authors:** Felix T.J. Dietrich

## Context

Before #1198, each vendor (GitHub, GitLab, Slack-as-notification) had its own
hand-rolled adapter, its own webhook route, its own message-handler registry,
and its own credential storage path on the `Workspace` row. Three external
modules (`workspace/`, `contributors/`, `gitprovider/common/github/app/`)
imported vendor classes directly. Adding more vendors would have compounded that
linearity. This ADR records the unified surface the codebase now ships against
so the next vendor doesn't have to re-litigate it.

Outline integration was an out-of-scope non-goal for this epic and does not exist
in the shipped tree (deferred to #1203). No `OUTLINE` value ships in
`IntegrationKind` today — the enum is `GITHUB`, `GITLAB`, `SLACK`.

> Superseded for `OUTLINE` by [ADR 0023](0023-outline-documentation-integration.md), which adds
> `OUTLINE` (family `DOCUMENTATION`) to `IntegrationKind`. The enumeration above is as-of-writing.

## Decision drivers

- New-vendor cost has to scale flat, not linearly.
- Vendor-specific vocabulary (`installation_id`, `whsec_*`, `xoxb-*`) cannot
  leak into the abstraction or into `Workspace`.
- Cross-module access must be enforceable by ArchUnit + Spring Modulith, not
  by convention.
- Credentials at rest must bind to row identity (AAD), not be substitutable
  across rows.
- The runtime cutover has to be atomic — partial states are worse than the
  pre-#1198 baseline.

## Considered options

1. **Status quo** — extend `gitprovider/` for each new family. Rejected:
   the package name says "git" and every consumer learns to mentally translate.
2. **Per-family roots** (`scm/`, `messaging/`, `knowledge/` as siblings,
   vendor under family). Rejected: ~600 file moves, ~3000 import rewrites, and
   the cross-cutting `platform/` pile reintroduces what we were trying to
   eliminate.
3. **Pipeline-first** — collapse `webhook/`, `oauth/`, `consumer/`, `handler/`
   into one `ingest/`. Rejected: webhook (HMAC, raw body) and oauth (signed
   state, browser redirect) have different security models; grouping them
   obscures more than it reveals.
4. **Cross-cutting substrate under `core/`, SCM family under `scm/`** — adopted.
   `core/` holds the vendor-neutral pipeline; `scm/` is the SCM family root with
   the shared domain kernel plus per-vendor adapters; single-vendor families
   (Slack) sit at the top level. This keeps the pipeline's security boundaries
   (webhook vs oauth) distinct while letting a new SCM vendor add one package.

## Decision

The integration domain lives under `integration/` with the following top-level
shape:

```text
integration/
├── core/   vendor-neutral substrate: spi, events, framework, connection,
│           consumer, handler, oauth, webhook, feedback
├── scm/    SCM family root: domain (shared kernel, 18 leaf packages),
│           github/, gitlab/ (vendor adapters), sync/ (family orchestrator)
└── slack/  single-vendor messaging adapter, Modulith OPEN, opt-in
            (matchIfMissing=false)
```

SPI vendor-name neutrality is enforced in naming (`InstallationCredential` not
`GithubAppCredential`, `AuthMode.INSTALLATION_APP` not `GITHUB_APP`), with
Modulith `allowedDependencies` declared explicitly on every vendor leaf and
`IntegrationStructuralRulesTest` pinning the invariants in ArchUnit. Identity
persistence is covered separately by
[ADR 0016](0016-unified-identity-keycloak-as-truth.md).

Detail of the substrate packages:

- `integration/core/spi/` — sole cross-module API surface (`@NamedInterface`).
- `integration/core/events/` — in-process `ScmDomainEvent` sealed family via
  Spring `ApplicationEventPublisher`. Wire-level publication is raw bytes plus
  vendor headers, in `integration/core/webhook/`. The event substrate is
  currently SCM-scoped (`ScmDomainEvent` + 2-value `GitProviderType`); a
  cross-vendor `IntegrationEvent` envelope is deferred to the later
  Slack/Outline integration work.
- `integration/core/connection/` — `Connection` aggregate root, audit log,
  sealed `ConnectionConfig`, AES-256-GCM `CredentialBundleConverter`,
  `EncryptionContext` AAD. Also hosts the platform-level
  `GitProvider`/`GitProviderType` metadata (vendor identity, not domain).
- `integration/core/framework/` — `IntegrationFrameworkBootstrap`,
  `IntegrationManifestRegistry`, `WorkspaceCapabilityResolver`. Module
  startup + capability resolution.
- `integration/core/webhook/` — unified `WebhookController`
  (`POST /webhooks/{kind}`), ingest pipeline, JetStream publisher, dedup,
  payload size filter.
- `integration/core/consumer/` — NATS consumer + dispatcher + poison handler.
- `integration/core/handler/` — `IntegrationMessageHandler` SPI + `EventTypeKey`
  registry.
- `integration/core/oauth/` — outbound OAuth callback + signed-state nonce store
  (RFC 9700 single-use signed state (HMAC + TTL + nonce)).
- `integration/core/feedback/` — `FeedbackPost` table for edit-in-place feedback
  identity across vendors.
- `integration/scm/` — the platform-agnostic SCM domain (formerly
  `gitprovider/`). Subpackages: `commit`, `issue`, `pullrequest`,
  `pullrequestreview`, `pullrequestreviewcomment`, `pullrequestreviewthread`,
  `label`, `milestone`, `team`, `user`, `repository`, `organization`,
  `discussion`, `discussioncomment`, `issuecomment`, `issuetype`, `project`,
  `sync`, `workdir` (filesystem clone manager, NOT git-domain), `common`.
- `integration/scm/github/`, `integration/scm/gitlab/` — SCM vendor adapters
  under the `scm/` family root. Each carries its own `webhook/`, `lifecycle/`,
  `credentials/`, `manifest/`, `connect/` (OAuth/PAT setup) subpackages;
  GitHub adds `app/`, `installation/`.
- `integration/slack/` — Slack vendor adapter (messaging, OAuth connect,
  webhook, lifecycle, leaderboard fan-out). Ships a live weekly-leaderboard
  notification path; the `SlackMessage`/`SlackChannel` write-dead persistence
  layer was removed in this epic (see Consequences). Declared
  `Type.OPEN` by empirical necessity — the leaderboard fan-out crosses module
  boundaries; full CLOSED is deferred. Opt-in (matchIfMissing=false).

The SPI in `integration/core/spi/` is organised on three axes — **lifecycle**
(provisioning + membership listeners, `ConnectionStrategy`), **wire** (signature
verification, subject parsing, token/credential sources), and **capability**
(feedback/finding/approval channels, sync context + timestamp providers,
manifest). Each interface is single-purpose and capability-gated: a vendor
implements only what its `IntegrationManifest` declares. Many are single-
implementer dependency-inversion seams against `workspace/` rather than
multi-vendor extension points, so the interfaces that matter for adding a third
SCM vendor are essentially the wire and sync-capability axes.

Credentials at rest use AES-256-GCM with AAD bound to
`(workspaceId, kind, instanceKey, columnFqn)`. The ciphertext envelope is
`[version-byte | 12-byte IV | ciphertext+16-byte GCM tag]`. Cross-row
substitution attacks fail because the AAD doesn't match.

The Liquibase migration is one file, `1780313973588_changelog.xml`,
25 changesets. Changeset 8 runs an idempotent `WorkspaceConnectionBackfillChange`
Java customChange that re-wraps legacy `Workspace` credentials into `connection`
rows with AES-GCM v2 blobs. Changeset 9 drops the legacy `Workspace` columns
only after verifying that the backfill succeeded. The changelog header documents
the encryption-key requirement for the backfill step.

## Consequences

Positive:

- Adding the third SCM vendor (Bitbucket / Gitea / Forgejo) requires only
  `integration/<vendor>/` plus the SPI implementations the manifest declares.
  Zero changes elsewhere.
- ArchUnit pin tests in `IntegrationCutoverPinsTest` make regression
  expensive: re-introducing `..gitprovider..`, posting to legacy `/github`
  or `/gitlab` routes, or re-adding legacy `installationId`/`personalAccessToken`
  fields on `Workspace` all fail the build.
- Spring Modulith `ApplicationModules.of(...).verify()` (pinned by
  `ModulithVerificationTest`) catches cyclic dependencies and missing
  `@NamedInterface` grants.
- The Connection aggregate is the only authority for "which vendor is
  active for this workspace." There is no second source of truth.

Neutral:

- One-file changelog with 25 changesets is non-idiomatic; Liquibase
  guidance prefers one file per logical change. Defensible here because
  the file IS the unit of release for this epic — splitting it would
  spread one decision across 12 files for no operational benefit.
- The SPI surface is broad, but several interfaces are single-implementer DI
  seams against `workspace/` rather than vendor extension points (see Decision).
  Each interface is small; the cost is conceptual surface area, not LOC.

Negative:

- The API contract break (`gitProviderMode` → `kind`) requires regenerating
  every client. The webapp client was regenerated in this epic; future external
  API consumers must port at adoption time.
- The backfill (`WorkspaceConnectionBackfillChange`) requires the encryption
  key at migration time. If the key is absent the changeset fails loudly
  (not silently) before any legacy columns are dropped. Operators must ensure
  `hephaestus.security.encryption-key` is set before applying the migration.
- Slack ships with a live weekly-leaderboard notification path
  (`SlackLeaderboardDigestPublisher` → `SlackMessageService`), plus
  connect/credentials/webhook/OAuth scaffolding. The write-dead Slack
  persistence layer (`SlackMessage`/`SlackChannel` entities + repos +
  deletion handler) was removed in this epic; `SlackLifecycleListener` is a
  no-op stub. The Connection table accepts SLACK rows.

## Revisit trigger

- A new vendor whose conceptual model doesn't fit the three axes (lifecycle /
  wire / capability) — e.g. an event-source-only vendor with no webhook,
  no OAuth, no PAT, that pushes events via a third-party broker. If that
  shape arrives, the SPI axes need a fourth row, not a forced fit.
- A second cross-cutting trait that needs its own top-level package
  (`oauth/` already does; `webhook/` already does). If a third such trait
  appears (e.g. `outbound-http/` for vendor-side API calls that aren't
  webhooks or OAuth), the `integration/` root would gain a third sibling
  and the package layout should be re-evaluated.
- Cross-vendor subject linking (PR ↔ Outline doc, Slack thread ↔ MR) at
  scale — the current shape is per-vendor rows in `connection` plus
  per-subject rows in `feedback_post`. A scale-out would need a dedicated
  link table, not a generic graph.

## Update — 2026-09-17

Corrects names and paths in § Decision and § Consequences; the layout and the SPI axes stand.

- `integration/core/feedback/` and `FeedbackPost` were deleted on 2026-07-01 (#1331). Cross-vendor
  feedback identity is `practices/feedback/Feedback` and `FeedbackPlacement.posted_comment_ref`
  ([ADR 0021](0021-observations-feedback-synthesis-seam.md),
  [ADR 0022](0022-observation-presence-assessment-and-schema-cleanup.md)); the third Revisit-trigger
  bullet's `feedback_post` table does not exist.
- `GitProvider` / `GitProviderType` are `IdentityProvider` / `IdentityProviderType`
  (`integration/core/connection/`, table `identity_provider`).
- `WorkspaceCapabilityResolver` exists nowhere in the repository; capability resolution is
  `IntegrationManifestRegistry.capabilitiesFor(IntegrationKind)` in `integration/core/framework/`.
- `integration/core/` also holds `egress`, `fabric`, `graphql`, `metrics`, `signal` and `sync`;
  `integration/outline/` is the fourth adapter ([ADR 0023](0023-outline-documentation-integration.md));
  `integration/identity/connect/` holds `RegistrationToGitProviderResolver`.
- `1780313973588_changelog.xml` is archived at `docs/db/archive/v0.77.4/changelog/`; the shipped
  chain starts at `server/application/src/main/resources/db/changelog/0000000000000_baseline_v0_77_4.xml`
  ([ADR 0014](0014-per-row-aes-gcm-aad-binding.md) § Update 2026-09-17).
