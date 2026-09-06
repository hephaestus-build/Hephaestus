# Architecture Decision Records

Decisions that shape the Hephaestus server foundations. Each ADR follows the
[MADR](https://adr.github.io/madr/) template: context → drivers → considered options
→ decision → consequences → revisit trigger.

New ADRs use the next available number and link from this index.

## These are repo-only — deliberately not on the docs site

`docs/decisions/`, `docs/runbooks/`, `docs/auth-architecture.md` and `docs/auth-glossary.md` are
**not** registered with any Docusaurus plugin and never appear on
[the site](https://docs.hephaestus.build/). That is a decision, not an oversight. They are
written against internal class names, they are amended by the same PR that changes the code, and
their reader already has the repository checked out. This index is their table of contents.

Two rules follow, and CI enforces the second one:

1. **An ADR is a record of a decision, not a changelog.** Context, drivers, options, decision,
   consequences, revisit trigger. A bug fixed after the fact belongs in a changeset
   (`.changeset/*.md`), not in an amendment — amend an ADR only when the decision or one of its
   consequences actually changed.

   **An amendment appends; it never rewrites.** Add a dated `## Update — YYYY-MM-DD (issue #N)`
   block at the end, say which section above it supersedes, and leave that section standing. This
   applies hardest to the considered-and-rejected options: they are the reason a future engineer
   does not re-propose a path that was already priced and declined, and deleting them is what turns
   an ADR into the changelog rule 1 forbids. If a previously rejected option is now the chosen path,
   the update block says so and dates it. [ADR 0005](0005-two-role-runtime-via-conditional-on-property.md)
   is the worked example.
2. **A published page links here by absolute GitHub URL, never a relative path.** A relative
   `../decisions/…` link from `docs/user/`, `docs/contributor/` or `docs/admin/` resolves to
   nothing on the site, and `onBrokenMarkdownLinks: 'throw'` fails the docs build. Use
   `https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/<file>.md`. Relative links
   *between* files in this tree are fine — they are only ever read on GitHub.

An operator-facing fact must not live only here. If a runbook step is something a self-hoster has to
do, its home is the Admin Guide (`docs/admin/`) and the runbook links to it.

| # | Title | Status |
|---|---|---|
| [0001](0001-flat-top-level-layout.md) | Flat top-level layout | Accepted |
| [0002](0002-java-package-rename-to-aet-cit-tum.md) | Rename Java base package to `de.tum.cit.aet.hephaestus` | Accepted |
| [0003](0003-spring-modulith-adoption.md) | Spring Modulith 2.0 adoption with pragmatic shared kernels | Accepted |
| [0004](0004-sql-layer-tenancy-via-statement-inspector.md) | SQL-layer tenancy enforcement via WorkspaceStatementInspector | Accepted |
| [0005](0005-two-role-runtime-via-conditional-on-property.md) | Two-role runtime topology via `@ConditionalOnProperty` | Accepted (amended 2026-05-20 #1110, 2026-07-20 / 2026-07-21 / 2026-07-22 #1368, 2026-08-22 — a setting must reach a container that can read it; 2026-09-03 #1719 — role count fixed at three by [0041](0041-compose-1x-kubernetes-2.md)) |
| [0006](0006-llm-proxy-on-coordinator-trust-model.md) | LLM proxy stays on the coordinator (BYO trust model); extended to the server-side OpenAI-compatible model catalog | Accepted (amended 2026-07-20 #1368, 2026-07-28 #1400, 2026-09-03 #1719 — proxy placement superseded by [0041](0041-compose-1x-kubernetes-2.md)) |
| [0007](0007-sandbox-spi-shape.md) | Sandbox SPI shape — sealed VolumeMount + typed NetworkPolicy | Accepted (amended 2026-09-03 #1719 — mount portability superseded by [0041](0041-compose-1x-kubernetes-2.md)) |
| [0008](0008-webhook-runtime-role.md) | Webhook as a third runtime role (`webhook-server` container) | Accepted (amended 2026-08-22 — webhook ingestion cannot fail silently) |
| [0009](0009-worker-runtime-substrate-wss-control-channel.md) | Worker runtime substrate over WSS control channel | Accepted (amended 2026-07-21 #1368, 2026-09-03 #1719 — remote-worker premise superseded by [0041](0041-compose-1x-kubernetes-2.md)) |
| [0010](0010-outbound-oauth-state-handrolled.md) | Outbound OAuth state hand-rolled (HMAC-signed, no library) | Accepted |
| [0011](0011-integration-identity-not-wired-from-sync.md) | `integration_identity` is OAuth-fed, not sync-fed | Superseded by [0016](0016-unified-identity-keycloak-as-truth.md) |
| [0012](0012-cross-instance-identity-safety-on-sync-paths.md) | Cross-instance identity safety on sync paths | Accepted |
| [0013](0013-no-jetstream-dlq-stream.md) | No JetStream DLQ stream (in-place NAK with backoff) | Accepted |
| [0014](0014-per-row-aes-gcm-aad-binding.md) | Per-row AES-GCM AAD binding for credential storage | Accepted |
| [0015](0015-unified-integration-framework.md) | Unified integration framework — single SPI for SCM/messaging vendors | Accepted (amended 2026-05-27 for Phase 1-4 restructure) |
| [0016](0016-unified-identity-keycloak-as-truth.md) | Unified identity — Keycloak `sub` as the authoritative join key | Accepted |
| [0017](0017-replace-keycloak-with-spring-native-auth.md) | Replace Keycloak with Spring-native auth (BFF cookie-JWT + `Connection`-backed workspace IdPs) | Accepted |
| [0018](0018-pg-partman-for-auth-event-partitioning.md) | `pg_partman` for `auth_event` partitioning | Accepted |
| [0019](0019-workspace-membership-keyed-on-account.md) | Workspace membership is keyed on `Account`, not the SCM `User` | Proposed |
| [0020](0020-context-fabric-everything-is-an-integration.md) | Context Fabric: everything is an integration | Accepted (amended 2026-08-04 #1430, 2026-08-30 #1636 — evidence storage superseded by [0039](0039-git-and-postgresql-own-evidence.md); 2026-09-03 #1719 — context mechanism superseded by [0041](0041-compose-1x-kubernetes-2.md)) |
| [0021](0021-observations-feedback-synthesis-seam.md) | Findings vs feedback — evidence and delivered feedback are separate records | Accepted (amended 2026-07-31 #1423, 2026-08-16 #1430) |
| [0022](0022-observation-presence-assessment-and-schema-cleanup.md) | Observation = presence × assessment (drop `Practice.kind`); reaction anchors on feedback; ruthless column cleanup | Accepted |
| [0023](0023-outline-documentation-integration.md) | Outline documentation integration — a content source, not a detection surface | Accepted |
| [0024](0024-integration-sync-lifecycle-and-two-deletion-semantics.md) | Integration sync lifecycle — drift tombstones and mirror erasure are two different operations | Accepted (amended 2026-09-03 #1404 — which reads honour a drift tombstone; 2026-09-04 #1806 — a re-offered signal is held, not retired; 2026-09-05 #1825 — live occasions share the reversible hold; 2026-09-05 #1404 — capture, new feedback and the two non-SCM kinds) |
| [0025](0025-agent-job-queue-on-postgresql.md) | Agent job queue moves off NATS onto PostgreSQL | Accepted |
| [0026](0026-per-purpose-agent-bindings-and-llm-governance.md) | Per-purpose agent bindings and governed OpenAI-compatible LLM catalog | Accepted (amended 2026-07-26 — named-agent-config model deleted) |
| [0027](0027-dialog-lifetime-and-where-a-write-outcome-lands.md) | Dialog lifetime, and where a write's outcome lands when the dialog is gone | Accepted |
| [0028](0028-source-synced-practice-catalog.md) | Source-synced practice catalog with sparse instance overrides | Accepted |
| [0029](0029-measurement-intervention-seam-and-channel-levels.md) | Measurement and intervention are separate turns; a channel names where feedback lands, and its level follows | Accepted |
| [0030](0030-agent-runtime-is-typescript-on-bun.md) | The agent runtime is TypeScript on Bun, with no Node in the sandbox | Superseded by [0036](0036-agent-runtime-runs-on-node-24.md) |
| [0031](0031-agent-image-follows-the-deployments-own-tag.md) | The agent image reference follows the deployment's own image tag | Superseded by [0034](0034-signed-release-image-lock.md) |
| [0032](0032-generated-clients-build-boundary.md) | Generated clients are a Maven build boundary | Accepted |
| [0033](0033-bun-is-the-javascript-runtime-and-package-manager.md) | Bun is the JavaScript runtime and package manager | Superseded by [0037](0037-node-24-and-pnpm-12-are-the-javascript-toolchain.md) |
| [0034](0034-signed-release-image-lock.md) | Production consumes one signed release image lock | Accepted (amended 2026-09-03 #1719 — [0041](0041-compose-1x-kubernetes-2.md) adds the 2.0 chart and CRD inventory) |
| [0035](0035-pull-request-previews-are-label-gated.md) | Pull request previews are label-gated and driven from the default branch | Accepted (amended 2026-09-03 #1719 — agent execution host superseded by [0041](0041-compose-1x-kubernetes-2.md)) |
| [0036](0036-agent-runtime-runs-on-node-24.md) | Agent runtime runs on Node.js 24 with bounded resources | Accepted |
| [0037](0037-node-24-and-pnpm-12-are-the-javascript-toolchain.md) | Node.js 24 and pnpm 12 are the JavaScript toolchain | Accepted (amended 2026-09-03 — [0040](0040-vite-plus-is-the-command-surface.md) makes Vite+ the command surface) |
| [0038](0038-postgresql-18-release-baseline.md) | PostgreSQL 18 is the qualified release baseline | Accepted (amended 2026-08-31 #1673 — the Compose volume keeps its stable `postgresql-data` name) |
| [0039](0039-git-and-postgresql-own-evidence.md) | Git owns repository evidence; PostgreSQL owns captured payloads and references | Accepted (amended 2026-09-03 #1719 — evidence retention and replay superseded by [0041](0041-compose-1x-kubernetes-2.md)) |
| [0040](0040-vite-plus-is-the-command-surface.md) | Vite+ is the command surface; pnpm stays the package manager | Accepted |
| [0041](0041-compose-1x-kubernetes-2.md) | Compose for 1.x; Kubernetes only for 2.0 | Accepted |
| [0042](0042-hosts-pull-their-own-releases.md) | Hosts pull their own releases | Accepted |

Template: [0000-template.md](0000-template.md).
