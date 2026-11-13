# ADR 0096: 1.0 is one stopped cutover from 1.0.0-rc.1, and it keeps nothing for 0.x

**Status:** Accepted
**Date:** 2026-11-10
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0070](0070-1-0-is-a-cutover-release.md)

**Builds on:** [ADR 0080](0080-provider-events-go-to-a-postgresql-inbox.md), [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md), [ADR 0091](0091-the-signed-helm-chart-is-the-only-supported-deployment-and-one-host-runs-it-on-k3s.md)

**Relates to:** [ADR 0092](0092-ci-builds-each-image-once-a-release-signs-one-lock-and-hosts-pull-it.md)

## Context

ADR 0070 decided that 1.0 is a stopped cutover with no compatibility code for 0.x.
It has four updates and one edit in place, and parts of it no longer match main.
[#3712](https://github.com/hephaestus-build/Hephaestus/pull/3712) replaced the 0.x changelog chain with a baseline of 1.0.0-rc.1. The minimum source is thus no longer 0.95.x.
ADR 0080 removes NATS and the webhook role, so the JetStream steps no longer apply.
On 2026-11-11 the maintainer decided that 1.0 converges onto its final state: no deferral to 1.1, and unused code, flags and paths go.
This ADR states the cutover at 1.0 as rules, and it holds the one list of breaking changes.

[Upgrade to 1.0](../admin/upgrade-to-1-0.mdx) owns the procedure. [The 1.0 baseline](../contributor/database-migration.mdx#the-10-baseline) owns the schema mechanics.

## Decision drivers

- 1.0.0 defines the public contract. Each shim that 1.0 ships becomes part of the 1.x promise.
- The maintainers operate the only two known installations ([ADR 0070](0070-1-0-is-a-cutover-release.md#context)), so one outage costs little now and nothing later.
- An operator reads one page to upgrade, and each step on it names its migration note.

## Considered options

1. **One stopped cutover, and nothing kept for 0.x (chosen).** One outage. 1.x carries no compatibility code.
2. **Rolling upgrades into the 1.0 release.** Each removal takes two releases. The release then ships compatibility code that it must keep.
3. **Ship 1.0 and remove the rest in 1.1.** Under SemVer, a removal after 1.0.0 is a major change. Thus, "1.1" would be 2.0.
4. **Keep the 0.x changelog chain.** ADR 0070 chose this. The rc.1 baseline replaced it when a checked synchronization existed.

## Decision

1. **The upgrade into 1.0.0 is one stopped upgrade.** Every role of the previous release stops. Then 1.0.0 migrates and starts. No previous role runs beside a 1.0.0 role or on its schema.
   Enforced by: `scripts/k3s-platform.test.ts` ("the installer refuses release changes and uncertain release state before host changes") for one host. Not enforced for a pull-based host on a candidate: `isMajorCutover` sees no major change from rc.N to 1.0.0. Step 2 of the upgrade guide pauses the reconciler.
2. **The minimum source is the rc.1 release (`1.0.0-rc.1`).** The precondition of `master.xml` admits three histories. They are an empty database, the 1.0 baseline, and the last change set of `1.0.0-rc.1`. It refuses every other history, and its message names the rc.1 release. An older installation first installs the rc.1 release with that release's guide ([Database baseline prerequisites](../admin/compatibility-policy.mdx#database-baseline-prerequisites)).
   Enforced by: `LiquibaseBaselineIntegrationTest#shouldRefuseWithoutChangesWhenADatabaseIsOlderThanRc1` and `#shouldRefuseAFalseIdentityOfTheLastRc1ChangeSet`.
3. **A database on 1.0.0-rc.1 joins the baseline only with the same schema.** `BaselineSynchronization` compares the schema fingerprint with a new install of rc.1. If they match, it records the baseline without DDL. If they differ, or a later build ran, it refuses and writes nothing.
   Enforced by: `LiquibaseBaselineIntegrationTest` (`shouldEndInTheSameSchemaWhenADatabaseOnRc1Upgrades`, `shouldRefuseWithoutChangesWhenASchemaOnRc1Drifted`, `shouldRefuseWithoutChangesWhenABuildLaterThanRc1RanOnTheDatabase`) and `SchemaFingerprintDatabaseTest#shouldPinTheSchemaThatTheBaselineBuilds`.
4. **Work in flight resumes from its row.** The lease reaper requeues a run that a stopped role held ([ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) decision 3). A Heph turn is never answered late without the developer asking (F-15).
   1.0 target: on main, a turn whose holder stops goes back to the queue once (`TurnRunKind`, [#4072](https://github.com/hephaestus-build/Hephaestus/pull/4072)), against this rule. No pull request yet ends the stopped turn visibly.
5. **Released history never changes.** `master.xml` holds one include of the baseline and one `includeAll` of `next/`. A future baseline needs a major release, an automated synchronization and an ADR.
   Enforced by: `scripts/check-changelog-immutability.ts` (Security workflow, step "Changelog immutability guard").
6. **A release comes only from `main`.** No `release/0.95` or older 1.x branch exists. A fix for 0.x ships only as a 1.x release.
   Enforced by: `release.yml:76-78`, which refuses a commit that is not on `main`.

7. **1.0 keeps nothing for 0.x or for an earlier candidate.** No shim, alias, flag, second path or "for one release" value. The pull request that replaces a thing deletes it. The chart schema refuses a removed value. It never ignores it.
   Enforced by: `verify-changesets.ts --removed-chart-values` in `verify-changesets.yml`, which requires the changeset or note to name each removed chart value. [The retired-path gate](../contributor/local-verification.mdx#retired-paths) checks source, configuration, routes and docs.
8. **From 1.0.0 on, the rolling rules apply again.** Release N runs on the schema of release N+1. An upgrade that skips a 1.x release stops every role (F-16). A break of the public contract needs a major release.
   Enforced by: `scripts/check-changelog-compatibility.ts`, `release-upgrade.yml`, and `gate:public-contract`, which reads nothing below 1.0.0.

9. **Each breaking change has one `.migration` note, and the upgrade page names it.** A changeset with `**Operators:**` needs a note of the same slug, and the reverse. [Upgrade to 1.0](../admin/upgrade-to-1-0.mdx) orders the action of each note after the last candidate.
   Enforced by: `gate:changesets` for the pair. `gate:docs-pages` fails when no upgrade page names the heading of a note.
10. **A note that a later 1.0 change makes moot goes in that change.** An operator never acts on a thing that 1.0 deletes. The NATS deletion removes the four NATS notes and the NATS prerequisites of the upgrade page.
    Not enforced. Review of each deletion pull request catches it.

`MIGRATION.md` § v1.0.0-rc.2 to § v1.0.0-rc.5 hold 24 released entries that a host on rc.1 crosses. They never change.
The rows below are what 1.0.0 adds. "Main" is a note in `.migration/` on `main`.

| Change | What the operator does | State |
| --- | --- | --- |
| [Stop every role](../../.migration/release-1-0-0.md) | Follow the upgrade page. Restore the backup to go back. | Main |
| [Review leases on agent runs](../../.migration/agent-runs-for-reviews.md) | Stop every role. `hephaestus.agent.max-retries` becomes `hephaestus.agent.capacity.review-retries`. | Main |
| [Conversation loop](../../.migration/conversation-loop.md) | Stop every role. Add `HEPHAESTUS_CONVERSATION_LOOP_SECRET`. Move `worker.conversationTurns` to `conversationLoop.turns`. | Main |
| [Issue comment counts](../../.migration/issue-comment-coverage.md), [unique monitor names](../../.migration/unique-repository-monitor-names.md) | Stop every role. Remove duplicate monitors. Run a backfill after. | Main |
| [Database roles](../../.migration/least-privilege-database-roles.md) | Move the Secret to one login for each purpose ([#4013](https://github.com/hephaestus-build/Hephaestus/pull/4013)). | Main |
| [Chart lists](../../.migration/chart-list-inputs.md), [sync settings](../../.migration/sync-settings.md), [`small` profile](../../.migration/one-single-node-rollout-profile.md), [Git checkout switch](../../.migration/required-git-checkout.md), [incremental sync](../../.migration/incremental-sync-only.md) | Change or remove the named values. | Main |
| [Sandbox controller](../../.migration/chart-sandbox-templates.md), [gVisor download](../../.migration/platform-gvisor.md), [PostgreSQL certificate](../../.migration/internal-database-certificate-lifetime.md), [one-host procedure](../../.migration/one-host-stopped-upgrades.md) | Prepare the platform before the chart. | Main |
| [Trusted runtime](../../.migration/compile-the-trusted-runtime.md), [review tools sidecar](../../.migration/review-tools-sidecar.md), [script imports](../../.migration/script-author-surface.md) | Rebuild a custom agent image. Update custom practice scripts. | Main |
| [Team API](../../.migration/team-api-contract.md), [practice slugs](../../.migration/practice-route-slugs.md), [installation policy](../../.migration/github-installations-follow-creation-policy.md) | Update API callers. Run the slug preflight. Choose the workspace creation policy. | Main |
| [Consumer settings](../../.migration/fixed-broker-consumer-policy.md), [broker certificate](../../.migration/nats-broker-hostname.md), [NATS 2.11](../../.migration/provider-events-ordered-across-servers.md), [stream byte map](../../.migration/webhook-stream-byte-map.md) | NATS steps. | Main. Moot when NATS goes (rule 10). |
| [Worker values](../../.migration/server-runs-sandboxes.md) | The worker role goes. Move `worker.*` to `sandbox.*` and `server.*`. | Main ([#4079](https://github.com/hephaestus-build/Hephaestus/pull/4079)) |
| [Server receives webhooks](../../.migration/server-receives-webhooks.md) | The webhook role goes. Remove `webhook.replicas` and `webhook.resources`. | Main ([#4100](https://github.com/hephaestus-build/Hephaestus/pull/4100)) |
| [Turn leases on agent runs](../../.migration/turns-on-agent-runs.md) | Stop every role. `hephaestus.mentor.turn.*` keys move to `hephaestus.agent.capacity.*`. | Main ([#4072](https://github.com/hephaestus-build/Hephaestus/pull/4072)) |
| [Review poll settings](../../.migration/review-job-runtime-removed.md), [sandbox claims](../../.migration/erasure-stops-review-sandboxes.md) | Remove `AGENT_POLL_INTERVAL`, `AGENT_CLAIM_BATCH_SIZE` and the review Job metrics. Delete the remaining sandbox workloads before the new release starts. | Main ([#4082](https://github.com/hephaestus-build/Hephaestus/pull/4082)) |
| [Script model purposes](../../.migration/remove-script-model-purposes.md), [usage for each call](../../.migration/usage-per-call.md) | Stop every role. Decisions, Embeddings and Rerank connections go. Give each chat model **Max output tokens** or **Context window**. | Main ([#4075](https://github.com/hephaestus-build/Hephaestus/pull/4075)) |
| [`permit-bands`](../../.migration/permit-bands.md) | Change queries on the `class` and `queue` labels to `band`. | On main ([#4095](https://github.com/hephaestus-build/Hephaestus/pull/4095)) |
| [Problem responses](../../.migration/problem-response-wire.md) | Read problem fields from the top level of the body. | Main ([#4089](https://github.com/hephaestus-build/Hephaestus/pull/4089)) |
| [Root HTTPS issuer](../../.migration/root-https-issuer.md), [webhook receiver base](../../.migration/canonical-webhook-origin.md), [Slack callback](../../.migration/slack-canonical-callback.md) | Set a root HTTPS issuer. Remove the webhook URL override. Register the canonical Slack callback. | Main ([#4160](https://github.com/hephaestus-build/Hephaestus/pull/4160)) |
| [Feedback retention](../../.migration/feedback-retention.md) | Choose the retention. Rename `config.conversationRetention` to `config.feedbackRetention`. | Main ([#4166](https://github.com/hephaestus-build/Hephaestus/pull/4166)) |
| [Public activity names](../../.migration/public-activity-and-feedback-nav.md) | Turn on **Show contributors without an account** to keep their names on the public page. | Main ([#4155](https://github.com/hephaestus-build/Hephaestus/pull/4155)) |
| Membership auto-seeder | Remove `hephaestus.workspace.auto-seed-membership`. The chart never set it, so no note. | Merged ([#4111](https://github.com/hephaestus-build/Hephaestus/pull/4111)) |
| PostgreSQL inbox, no NATS ([ADR 0080](0080-provider-events-go-to-a-postgresql-inbox.md)) | Drain the streams. Remove `nats.*`, `webhook.stream.*` and `config.consumerInactiveThreshold`. | Decided, not shipped. No pull request delivers it yet. |
| The server serves the web app (ADR 0100) | Remove `webapp.*`. The web app image, the Gateway `/api` rewrite and `hephaestus.auth.api-base-path` go. | Decided, not shipped. No pull request delivers it yet. |
| No deletion cooldown | Remove `hephaestus.auth.delete-cooldown`. After a recent sign-in, an account is purged at once. | Decided, not shipped. No pull request delivers it yet. |
| No `preview` chart profile | Remove `profiles/preview.yaml` from Helm commands. | Decided, not shipped. No pull request delivers it yet. |
| `/mentor` redirects and `mentor` identifiers | Rename every `mentor` name once, at 1.0, and drop the `/mentor` redirects ([ADR 0070 update](0070-1-0-is-a-cutover-release.md#update--2026-10-28-one-product-name-and-conversation-turns)). | Decided, not shipped. No pull request delivers it yet. |

## What changed and why

- **The minimum source is 1.0.0-rc.1, not 0.95.x** (ADR 0070 § Minimum source version). #3712 re-baselined at rc.1. The path from 0.x ends at rc.1, and its versioned guide owns that path.
- **1.0 re-baselined** (ADR 0070 § Schema step). ADR 0070 declined it because no automated synchronization existed, and a plain sync can hide drift. `BaselineSynchronization` now checks a fingerprint before it records anything.
- **F-3 was not taken.** `release.yml` releases only from `main`, and the [support statement](../admin/compatibility-policy.mdx#support-statement) allows no backport branch.
- **The JetStream and worker steps go** (ADR 0070 § Procedure, § In-flight work). ADR 0080 and ADR 0081 remove NATS, the webhook role and the worker role. The exception for the webhook NATS contract goes with them.
- **The 0.x conversion of jobs and turns** belonged to the upgrade into the rc.1 release. The final release has no 0.x rows to convert.
- **F-2 is recorded here.** The legacy feedback link resolver is gone. ADR 0070 changed F-2 in place, against the ADR rules.

## Consequences

- Good: one outage for each installation, and one page that names each step.
- Good: 1.x carries no compatibility code, and a new install runs one baseline.
- Bad: an installation older than the rc.1 release takes several hops. It goes to 0.95.0 on the chart and to 0.95.1 with its reconciler tooling. Then it goes to rc.1 and to the final release.
- Bad: a database on rc.1 with a drifted schema refuses to upgrade. Its operator needs the maintainers.
- Bad: the reconciler does not see a stopped cutover from a candidate. The operator pauses it by hand.
- Bad: provider deliveries in the window reach no receiver. GitHub redelivers the last two hours, and the sync recovers the rest.
- Bad: many settings go at once, so each operator edits the values once with many changes.

## Confirmation

Each rule names its test or gate.
Rule 10 has no check. Review of each deletion pull request catches it.

## Revisit trigger

- A host on a 1.0 candidate cannot reach 1.0.0 with the upgrade guide.
- Someone proposes a second baseline, or a 1.x release needs a break. Both are 2.0 decisions.

## Sources

- [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html): "Major version zero (0.y.z) is for initial development. Anything MAY change at any time." "Version 1.0.0 defines the public API." This is why each removal lands before 1.0.0 and why option 3 is a major release.
- GitLab, [Upgrade paths](https://docs.gitlab.com/update/upgrade_paths/): required stops are "versions of GitLab that you must upgrade to before upgrading to later versions". 1.0.0-rc.1 is such a stop (rule 2).
- Liquibase, [changelog-sync-to-tag](https://docs.liquibase.com/commands/utility/changelog-sync-to-tag.html): it "marks all undeployed changesets from your changelog up to the specified tag as executed". Nothing checks the schema, so rule 3 compares the fingerprint first.

## Already priced and declined

Each line links the section of ADR 0070 that priced the option.

- [Rolling upgrades into 1.0](0070-1-0-is-a-cutover-release.md#considered-options).
- [A manual `changeLogSyncToTag` without a schema check](0070-1-0-is-a-cutover-release.md#schema-step).
- [SQL alone requeues the claimed reviews](0070-1-0-is-a-cutover-release.md#update--2026-10-28-lease-recovery-requeues-the-claimed-reviews). SQL cannot bill an attempt.
- [Start with empty JetStream streams after a drain](0070-1-0-is-a-cutover-release.md#in-flight-work).
- [The Docker sandbox driver after 1.0, and the Compose deletion after 1.0](0070-1-0-is-a-cutover-release.md#rules-this-upgrade-overrides-once).
- From [decisions F-1 to F-18](0070-1-0-is-a-cutover-release.md#decisions-f-1-to-f-18-accepted-2026-10-28):
  - F-1: convert or delete the version-0 practice history.
  - F-2: keep the legacy feedback link resolver.
  - F-3: a `release/0.95` hotfix branch.
  - F-4: keep the v0.77.4 archive and `WorkspaceConnectionBackfillChange`.
  - F-5: the shared GitLab webhook endpoint.
  - F-6: no workspace subdomains in 1.0.
  - F-7: a maintenance page during the stop.
  - F-8: refuse plaintext rows without a conversion.
  - F-9: keep the GitHub Discussions and Projects V2 sync.
  - F-10: `NATS_ENABLED` as a setting.
  - F-13: `GITLAB_WORKSPACE_CREATION` as a permanent switch.
  - F-14: keep the public operations that no client calls.
  - F-15: run an interrupted Heph turn again without the developer.
  - F-16: a rolling upgrade that skips a 1.x release.
