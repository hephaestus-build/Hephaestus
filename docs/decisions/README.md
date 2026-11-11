# Architecture Decision Records

An architecture decision record (ADR) records one decision that shapes Hephaestus, the options that we rejected, and the reasons.
This index lists every ADR.
The rules below say how to write one and how it changes over time.

## These are repo-only, not on the docs site

`docs/decisions/`, `docs/auth-architecture.md` and `docs/auth-glossary.md` are not registered with a Docusaurus plugin.
They never appear on [the site](https://docs.hephaestus.build/).
This is a decision.
They use internal class names, the pull request that changes the code also changes them, and their reader has the repository open.

- **Link an ADR by absolute GitHub URL from a published page.** Use `https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/<file>.md`. A relative `../decisions/…` link from `docs/user/`, `docs/contributor/` or `docs/admin/` resolves to nothing on the site, and `onBrokenMarkdownLinks: 'throw'` fails the docs build. Relative links between files in this directory are correct.
- **An operator fact does not live only here.** If a self-hoster must do a step, the Admin Guide (`docs/admin/`) owns it, and the ADR links to it.

## How we write ADRs

The rules come from [Nygard](https://www.cognitect.com/blog/2011/11/15/documenting-architecture-decisions), [MADR 4](https://github.com/adr/madr/blob/4.0.0/template/adr-template.md), Olaf Zimmermann's guidance on [creating](https://ozimmer.ch/practices/2023/04/03/ADRCreation.html) and [reviewing](https://ozimmer.ch/practices/2023/04/05/ADRReview.html) ADRs, and the [AWS ADR process](https://docs.aws.amazon.com/prescriptive-guidance/latest/architectural-decision-records/adr-process.html).
Copy [the template](0000-template.md) and use the next free number.

### Shape

- **One decision per ADR.** The title states the decision as a sentence.
- **Context** gives the forces and the real constraint in a few sentences. Do not tell the story of an incident or list run numbers.
- **Considered options** give the real cost of each option. Never add an option that cannot work only to make the chosen one look good. Rejected options stay forever, so that nobody proposes them again without a new reason.
- **Decision** is a set of rules that a reviewer can check against a diff. Detailed design goes to a contributor page, and the ADR links it.
- **Consequences** include the bad ones.
- **Confirmation** names the test, ArchUnit rule or CI gate that fails when the code breaks the decision. If nothing checks it, write `Not enforced.` and say what review catches it. MADR 4 calls this section Confirmation.
- **Revisit trigger** names a signal that we can observe.
- **Sources** name one to three trusted sources and what each one changes in the decision: a standard or RFC, the official docs of the tool, a peer-reviewed paper, or the docs of a well-run open-source project. Read the source before you cite it. If we differ from common practice on purpose, say why in one sentence.
- **Length:** one or two pages. A longer ADR usually holds design that belongs on a contributor page.

### Status

The status line holds one value, and the index shows the same value:

| Status | Meaning |
| --- | --- |
| `Proposed` | The decision is under review in an open pull request. |
| `Accepted` | The decision is in force. |
| `Superseded by NNNN` | A later ADR replaces this one. Read that ADR. |
| `Deprecated` | The decision no longer applies, and no ADR replaces it. The last update gives the reason. |

A rejected proposal is a closed pull request, not a status.
"Superseded in part" is not a status. A partial change is an `**Amended by:**` link in the header.
The header holds `**Status:**`, `**Date:**` and `**Authors:**`, then optional relation lines with links: `**Supersedes:**`, `**Amends:**`, `**Amended by:**`, `**Builds on:**` and `**Relates to:**`.

### Correct, update or supersede

An ADR records a decision. It is not a changelog. A bug fix after the fact goes in a changeset (`.changeset/*.md`).

| Change | What to do |
| --- | --- |
| A name, a path or a link is stale, or the prose breaks the [writing standard](../contributor/simplified-technical-english.md) | **Correct** it in place. Add no update block. Git history records the change. Do not change what was decided. |
| One rule or one consequence of the decision changed | **Update.** Append `## Update — YYYY-MM-DD: <what changed>` with the reason. Never rewrite the text above it. If another ADR made the change, add it to `**Amended by:**`. |
| The Decision no longer holds as written, or the ADR would get its third update | **Supersede.** Write a new ADR that states what is true now. Cite each ADR that it replaces. List their rejected options under "Already priced and declined", with links. Set the status of each old ADR to `Superseded by NNNN`, and change nothing else in it. |
| The decision no longer applies, and nothing replaces it | **Deprecate.** Set the status to `Deprecated` and append one update with the reason. |

A new ADR can change an old decision.
It then says what changed and why, and it cites a source for the new reason.
Rejected options are never deleted. A superseded ADR keeps them, and the new ADR links them.

### Checks

`scripts/decisions-index.test.ts` runs in `vp run check`. It checks that:

- each ADR has a unique number and a heading with that number;
- each status is in the table above, and the index shows the same status;
- each ADR link in a header points to an existing ADR;
- the index lists each ADR exactly once;
- each Accepted ADR from 0081 has a `## Confirmation` section.

## Index

| # | Title | Status |
|---|---|---|
| [0001](0001-flat-top-level-layout.md) | Flat top-level layout | Accepted |
| [0002](0002-java-package-rename-to-aet-cit-tum.md) | Rename Java base package to `de.tum.cit.aet.hephaestus` | Accepted |
| [0003](0003-spring-modulith-adoption.md) | Spring Modulith 2.0 adoption with two empirical shared kernels | Superseded by [0099](0099-aggregates-own-their-lifecycle-and-module-boundaries.md) |
| [0004](0004-sql-layer-tenancy-via-statement-inspector.md) | SQL-layer tenancy enforcement via WorkspaceStatementInspector | Superseded by [0084](0084-every-statement-on-workspace-data-binds-its-workspace.md) |
| [0005](0005-two-role-runtime-via-conditional-on-property.md) | Two-role runtime topology via `@ConditionalOnProperty` | Superseded by [0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) |
| [0006](0006-llm-proxy-on-coordinator-trust-model.md) | LLM proxy stays on the coordinator (BYO trust model) | Superseded by [0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) |
| [0007](0007-sandbox-spi-shape.md) | Sandbox SPI shape — sealed VolumeMount + typed NetworkPolicy | Superseded by [0079](0079-one-agent-system.md) |
| [0008](0008-webhook-runtime-role.md) | Webhook as a third runtime role (`webhook-server` container) | Accepted |
| [0009](0009-worker-runtime-substrate-wss-control-channel.md) | Worker runtime substrate + WSS-over-443 control channel | Superseded by [0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) |
| [0010](0010-outbound-oauth-state-handrolled.md) | Hand-roll OAuth state for outbound per-workspace integrations | Accepted |
| [0011](0011-integration-identity-not-wired-from-sync.md) | `integration_identity` is OAuth-fed, not sync-fed (tombstone) | Superseded by [0016](0016-unified-identity-keycloak-as-truth.md) |
| [0012](0012-cross-instance-identity-safety-on-sync-paths.md) | Cross-instance identity safety on sync paths | Superseded by [0094](0094-a-provider-mirror-changes-only-on-a-definitive-answer-and-a-tombstone-is-not-an-erasure.md) |
| [0013](0013-no-jetstream-dlq-stream.md) | No dedicated JetStream DLQ stream — poison handler ACKs after N redeliveries | Accepted |
| [0014](0014-per-row-aes-gcm-aad-binding.md) | Per-row AES-GCM AAD binds ciphertext to record identity | Accepted |
| [0015](0015-unified-integration-framework.md) | Unified integration framework — package layout and SPI surface | Superseded by [0095](0095-one-adapter-for-each-provider-behind-a-vendor-neutral-spi.md) |
| [0016](0016-unified-identity-keycloak-as-truth.md) | Unified identity — SCM `User` is the authoritative person row, Keycloak `sub` is the persisted join key | Superseded by [0017](0017-replace-keycloak-with-spring-native-auth.md) |
| [0017](0017-replace-keycloak-with-spring-native-auth.md) | Replace Keycloak with Spring-native auth (BFF cookie-JWT + `Connection`-backed workspace IdPs) | Superseded by [0088](0088-a-person-signs-in-through-an-instance-login-provider-and-is-one-account.md) |
| [0018](0018-pg-partman-for-auth-event-partitioning.md) | pg_partman for `auth_event` partitioning (supersedes ADR 0017's self-managed partitions) | Accepted |
| [0019](0019-workspace-membership-keyed-on-account.md) | Workspace membership is keyed on `Account`, not the SCM `User` | Superseded by [0088](0088-a-person-signs-in-through-an-instance-login-provider-and-is-one-account.md) |
| [0020](0020-context-fabric-everything-is-an-integration.md) | Context Fabric — everything is an integration, only practice review and mentor are native | Superseded by [0090](0090-a-review-cites-only-captured-bytes-and-admission-verifies-each-citation-once.md) |
| [0021](0021-observations-feedback-synthesis-seam.md) | Separate observations from feedback | Superseded by [0056](0056-one-practice-model-for-items-and-scans.md) |
| [0022](0022-observation-presence-assessment-and-schema-cleanup.md) | Observation = presence × assessment (drop `Practice.kind`); reaction anchors on feedback; ruthless column cleanup | Superseded by [0056](0056-one-practice-model-for-items-and-scans.md) |
| [0023](0023-outline-documentation-integration.md) | Outline documentation integration — a content source, not a detection surface | Superseded by [0095](0095-one-adapter-for-each-provider-behind-a-vendor-neutral-spi.md) |
| [0024](0024-integration-sync-lifecycle-and-two-deletion-semantics.md) | Integration sync lifecycle — drift tombstones and mirror erasure are two different operations | Superseded by [0094](0094-a-provider-mirror-changes-only-on-a-definitive-answer-and-a-tombstone-is-not-an-erasure.md) |
| [0025](0025-agent-job-queue-on-postgresql.md) | Agent job queue moves off NATS onto PostgreSQL | Superseded by [0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) |
| [0026](0026-per-purpose-agent-bindings-and-llm-governance.md) | Per-purpose agent bindings and governed OpenAI-compatible LLM catalog | Superseded by [0083](0083-each-purpose-uses-one-governed-model-and-every-call-is-counted.md) |
| [0027](0027-dialog-lifetime-and-where-a-write-outcome-lands.md) | Dialog lifetime, and where a write's outcome lands when the dialog is gone | Accepted |
| [0028](0028-source-synced-practice-catalog.md) | Source-synced practice catalog with sparse instance overrides | Accepted |
| [0029](0029-measurement-intervention-seam-and-channel-levels.md) | Separate measurement from intervention and derive level from channel | Superseded by [0056](0056-one-practice-model-for-items-and-scans.md) |
| [0030](0030-agent-runtime-is-typescript-on-bun.md) | The agent runtime is TypeScript on Bun, with no Node in the sandbox | Superseded by [0036](0036-agent-runtime-runs-on-node-24.md) |
| [0031](0031-agent-image-follows-the-deployments-own-tag.md) | The agent image reference follows the deployment's own image tag | Superseded by [0034](0034-signed-release-image-lock.md) |
| [0032](0032-generated-clients-build-boundary.md) | Generated clients have an independent build boundary | Superseded by [0043](0043-gradle-java-build.md) |
| [0033](0033-bun-is-the-javascript-runtime-and-package-manager.md) | Bun is the JavaScript runtime and package manager | Superseded by [0037](0037-node-24-and-pnpm-12-are-the-javascript-toolchain.md) |
| [0034](0034-signed-release-image-lock.md) | Production consumes one signed release image lock | Superseded by [0092](0092-ci-builds-each-image-once-a-release-signs-one-lock-and-hosts-pull-it.md) |
| [0035](0035-pull-request-previews-are-label-gated.md) | Pull request previews are label-gated and driven from the default branch | Deprecated |
| [0036](0036-agent-runtime-runs-on-node-24.md) | Agent runtime runs on Node.js 24 with bounded resources | Superseded by [0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) |
| [0037](0037-node-24-and-pnpm-12-are-the-javascript-toolchain.md) | Node.js 24 and pnpm 12 are the JavaScript toolchain | Superseded by [0089](0089-pinned-node-and-pnpm-install-the-repository-and-vite-plus-runs-every-command.md) |
| [0038](0038-postgresql-18-release-baseline.md) | PostgreSQL 18 is the only supported major version | Accepted |
| [0039](0039-git-and-postgresql-own-evidence.md) | Git owns repository evidence; PostgreSQL owns captured payloads and references | Superseded by [0090](0090-a-review-cites-only-captured-bytes-and-admission-verifies-each-citation-once.md) |
| [0040](0040-vite-plus-is-the-command-surface.md) | Vite+ is the command surface; pnpm stays the package manager | Superseded by [0089](0089-pinned-node-and-pnpm-install-the-repository-and-vite-plus-runs-every-command.md) |
| [0041](0041-compose-1x-kubernetes-2.md) | Compose for 1.x; Kubernetes only for 2.0 | Superseded by [0091](0091-the-signed-helm-chart-is-the-only-supported-deployment-and-one-host-runs-it-on-k3s.md) |
| [0042](0042-hosts-pull-their-own-releases.md) | Hosts pull their own releases | Superseded by [0092](0092-ci-builds-each-image-once-a-release-signs-one-lock-and-hosts-pull-it.md) |
| [0043](0043-gradle-java-build.md) | Gradle owns the Java build | Accepted |
| [0044](0044-email-is-a-notification-transport.md) | Email is a notification transport, delivered through the event publication registry | Accepted |
| [0045](0045-activity-counts-work-and-never-ranks-people.md) | Activity counts and lists work; it never scores or ranks people | Superseded by [0093](0093-members-see-activity-a-workspace-can-publish-its-public-work-and-private-practice-feedback-has-one-reader.md) |
| [0046](0046-heph-follows-its-model-not-a-workspace-switch.md) | Heph follows its model, not a workspace switch | Accepted |
| [0047](0047-the-practice-profile-has-one-reader.md) | The Practice profile has one reader | Superseded by [0093](0093-members-see-activity-a-workspace-can-publish-its-public-work-and-private-practice-feedback-has-one-reader.md) |
| [0048](0048-installed-clients-sign-in-with-a-pkce-handoff.md) | Installed clients sign in with a PKCE handoff to their own revocable session | Accepted |
| [0049](0049-browser-extension-report-in-the-page.md) | The browser extension shows its report in the page and confirms changes in its own window | Accepted |
| [0050](0050-one-practice-standard-one-outcome.md) | One practice standard, one outcome | Superseded by [0085](0085-an-observation-has-one-of-four-outcomes-and-standing-reads-the-newest-decision.md) |
| [0051](0051-practices-across-the-workspace-counts-developers.md) | Practices across the workspace counts developers and never names one | Superseded by [0093](0093-members-see-activity-a-workspace-can-publish-its-public-work-and-private-practice-feedback-has-one-reader.md) |
| [0052](0052-activity-sorts-by-contributions-and-can-be-public.md) | Workspace activity sorts people by plain counts, and a workspace can publish it | Superseded by [0093](0093-members-see-activity-a-workspace-can-publish-its-public-work-and-private-practice-feedback-has-one-reader.md) |
| [0053](0053-workspace-addresses-are-a-presentation-origin.md) | A workspace address is a presentation origin, and sign-in stays on the apex | Accepted |
| [0054](0054-practice-visuals-and-guides.md) | Practice visuals and guides are themeable inline SVG and Markdown | Accepted |
| [0055](0055-practice-scripts-use-one-result-contract.md) | Practice scripts use one result contract | Accepted |
| [0056](0056-one-practice-model-for-items-and-scans.md) | One practice model for items and scans | Superseded by [0085](0085-an-observation-has-one-of-four-outcomes-and-standing-reads-the-newest-decision.md) |
| [0057](0057-one-heph-conversation-per-developer.md) | One Heph conversation per developer, on a stateless shared Heph host | Superseded by [0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md) |
| [0058](0058-the-helm-chart-is-the-only-supported-deployment-from-1-0.md) | The Helm chart is the only supported deployment from 1.0 | Superseded by [0091](0091-the-signed-helm-chart-is-the-only-supported-deployment-and-one-host-runs-it-on-k3s.md) |
| [0059](0059-previews-run-on-the-staging-cluster.md) | Previews run on the staging cluster | Deprecated |
| [0060](0060-the-kubernetes-floor-follows-upstream-support.md) | The Kubernetes floor follows upstream support | Superseded by [0091](0091-the-signed-helm-chart-is-the-only-supported-deployment-and-one-host-runs-it-on-k3s.md) |
| [0061](0061-review-seals-then-feedback-composes.md) | A practice review seals its results, then feedback is composed | Superseded by [0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md) |
| [0062](0062-the-item-is-the-review-scope.md) | The item is the review scope | Superseded by [0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md) |
| [0063](0063-each-practice-is-staged-when-it-ends.md) | Each practice is staged when it ends, and the review seals once | Superseded by [0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md) |
| [0064](0064-one-evidence-contract-for-every-producer.md) | One evidence contract for every result producer | Accepted |
| [0065](0065-standing-counts-the-newest-observation-of-each-item.md) | Standing counts the newest observation of each item | Superseded by [0085](0085-an-observation-has-one-of-four-outcomes-and-standing-reads-the-newest-decision.md) |
| [0066](0066-the-webhook-receiver-has-no-database.md) | The webhook receiver has no database | Superseded by [0080](0080-provider-events-go-to-a-postgresql-inbox.md) |
| [0067](0067-the-server-image-builds-with-buildpacks-and-cds.md) | The server image builds with Paketo Buildpacks and Application CDS | Accepted |
| [0068](0068-reviews-and-heph-run-on-one-agent-runtime.md) | Reviews and Heph run on one agent runtime | Superseded by [0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) |
| [0069](0069-practices-run-when-ready-and-stay-current-by-their-sources.md) | Practices run when they are ready and stay current by their sources | Superseded by [0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md) |
| [0070](0070-1-0-is-a-cutover-release.md) | 1.0 is a cutover release | Accepted |
| [0071](0071-every-user-visible-failure-is-observable.md) | Every user-visible failure is observable, through one first-party pipeline | Accepted |
| [0072](0072-few-dependencies-owned-slivers-kept-foundations.md) | Few dependencies — own a sliver, keep a foundation | Accepted |
| [0073](0073-product-feedback-is-worked-on-in-github.md) | Product feedback is worked on in GitHub | Accepted |
| [0074](0074-a-reviews-width-and-count-follow-the-provider.md) | A review's width and count follow the provider | Superseded by [0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) |
| [0075](0075-a-transaction-is-one-use-case-and-talks-to-nothing.md) | A transaction is one use case and talks to nothing | Accepted |
| [0076](0076-reads-are-shaped-like-the-screen.md) | Reads are shaped like the screen, entities are for writes | Accepted |
| [0077](0077-provider-mirrors-are-shared-and-the-monitored-scope-isolates-them.md) | Provider mirrors are shared, and the monitored scope isolates them | Superseded by [0084](0084-every-statement-on-workspace-data-binds-its-workspace.md) |
| [0078](0078-lifecycles-are-explicit-and-rigor-follows-the-damage.md) | Lifecycles are explicit, and rigor follows the damage | Accepted |
| [0079](0079-one-agent-system.md) | One agent system: one sandbox per subject on agent-sandbox, PostgreSQL as the only brain | Accepted |
| [0080](0080-provider-events-go-to-a-postgresql-inbox.md) | Provider events go to a PostgreSQL inbox, and the server receives them | Accepted |
| [0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) | One server role runs Hephaestus, and agent work runs in sandboxes that reach only the server | Accepted |
| [0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md) | A developer has one Heph conversation, and its ledger is Heph's memory | Accepted |
| [0083](0083-each-purpose-uses-one-governed-model-and-every-call-is-counted.md) | Each purpose uses one governed model within the member's AI choice, and every model call is counted | Accepted |
| [0084](0084-every-statement-on-workspace-data-binds-its-workspace.md) | Every statement on workspace data binds its workspace, and a shared mirror binds it through the monitored scope | Accepted |
| [0085](0085-an-observation-has-one-of-four-outcomes-and-standing-reads-the-newest-decision.md) | An observation has one of four outcomes, holds one current claim while its sources stay unchanged, and standing reads the newest decision | Accepted |
| [0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md) | A practice review stages, seals, then composes, and each stage records why it stops | Accepted |
| [0088](0088-a-person-signs-in-through-an-instance-login-provider-and-is-one-account.md) | A person signs in through an instance login provider and is one account in every workspace | Accepted |
| [0089](0089-pinned-node-and-pnpm-install-the-repository-and-vite-plus-runs-every-command.md) | Pinned Node.js and pnpm install the repository, and Vite+ runs every command | Accepted |
| [0090](0090-a-review-cites-only-captured-bytes-and-admission-verifies-each-citation-once.md) | A practice review cites only bytes that the server captured, admission verifies each citation once, and Hephaestus keeps the verdict, not the bytes | Accepted |
| [0091](0091-the-signed-helm-chart-is-the-only-supported-deployment-and-one-host-runs-it-on-k3s.md) | The signed Helm chart is the only supported deployment, and one host runs it on k3s | Accepted |
| [0092](0092-ci-builds-each-image-once-a-release-signs-one-lock-and-hosts-pull-it.md) | CI builds each image once, a release signs one lock that names it, and hosts pull that lock | Accepted |
| [0093](0093-members-see-activity-a-workspace-can-publish-its-public-work-and-private-practice-feedback-has-one-reader.md) | Members see activity, a workspace can publish its public work, and private practice feedback has one reader | Accepted |
| [0094](0094-a-provider-mirror-changes-only-on-a-definitive-answer-and-a-tombstone-is-not-an-erasure.md) | A provider mirror changes only on a definitive provider answer, and a tombstone is never an erasure | Accepted |
| [0095](0095-one-adapter-for-each-provider-behind-a-vendor-neutral-spi.md) | Each provider has one adapter behind a vendor-neutral SPI, and Outline is a documentation source with a link-only identity | Accepted |
| [0099](0099-aggregates-own-their-lifecycle-and-module-boundaries.md) | Each module owns its aggregates, declares its dependencies, and changes a lifecycle only through the aggregate | Accepted |
| [0100](0100-the-server-serves-the-web-app.md) | The server serves the web app | Accepted |

Template: [0000-template.md](0000-template.md).
