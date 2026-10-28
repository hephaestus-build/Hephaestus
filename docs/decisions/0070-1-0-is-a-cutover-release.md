# ADR 0070: 1.0 is a cutover release

**Status:** Accepted (amended 2026-10-28 — lease recovery requeues the claimed reviews)
**Date:** 2026-10-28
**Authors:** Felix T.J. Dietrich
**Amends:** ADR 0058 (§ Supported deployment, § Notice and removal, gate 10), ADR 0041, ADR 0042 and ADR 0005.
**Relates to:** ADR 0068 (§ Order, decision D) and ADR 0069.

## Context

Hephaestus 0.x carries compatibility code for older rows, old config keys, old URLs, old message shapes and a second deployment.
Rule 3 of the changelog policy makes each release run on the schema of the next.
Thus, every removal takes two releases.
1.0 starts the 1.x guarantee.
Before 1.0, changes are cheap.
The maintainers operate the only two known installations (ADR 0058 § Context).

## Decision drivers

- 1.0 starts a compatibility promise. Every shim that 1.0 ships becomes part of that promise.
- The maintainers operate the only two known installations, so a stopped upgrade costs little now and nothing later.
- Self-hosting is the Helm chart on Kubernetes only (Felix, 2026-10-27), so 1.0 has one deployment to upgrade.

## Considered options

- **A stopped cutover at 1.0 (chosen).** One outage. 1.0 carries nothing for 0.x.
- **Rolling upgrades into 1.0.** Every removal takes two releases, and 1.0 ships the compatibility code that it then must keep.
- **A schema re-baseline at 1.0.** Rejected below, under Schema step.

## Decision

1. **The upgrade from 0.x to 1.0 is a stopped upgrade.** The old version stops. Then 1.0 migrates and starts. No 0.x role runs beside a 1.0 role, and no 0.x role runs on the 1.0 schema.
2. **1.0 keeps nothing for 0.x.** No shim, no alias, no second path, no "for one release" value. Each layer deletes what it replaces, in the same pull request (ADR 0068 § Order).
3. **From 1.0.0 on, the rolling rules apply again.** Release N runs on the schema of release N+1. Expand and contract applies.

### Minimum source version

- The minimum source is **0.95.x on the Helm chart.** 0.95.0 is the last 0.x release.
- A Compose install first upgrades to 0.95.x on Compose. Then it moves to the chart at the same version (ADR 0058 § Migration of a Compose install). Then it upgrades to 1.0.
- A database below 0.95.0 upgrades to 0.95.x first. That release still owns the v0.77.4 baseline path.
- 1.0 enforces the minimum. The global precondition in `master.xml` refuses a nonempty history without the final change set of 0.95.0. The refusal names 0.95.0. Fresh databases initialize as before.

### Procedure (Helm; Compose installs are on the chart before this step)

It is the [Database maintenance](../admin/kubernetes.mdx#database-maintenance) procedure, with the 1.0 chart in phase 2:

1. Before phase 1: back up PostgreSQL and the JetStream data, verify that the backup restores, and record `worker.replicas`.
2. **Phase 1, stop the writers.** Run `helm upgrade` with the verified 0.95 chart and values, `server.replicas=0` and `worker.replicas=0`. Wait until the server Pods, the worker Pods, and every sandbox Pod and Job are gone. The webhook role keeps receiving into JetStream.
3. **Phase 2 + 3, migrate and start the server alone.** Verify the 1.0 chart. Run `helm upgrade` with the 1.0 chart, `server.replicas=1` and `worker.replicas=0`. Do not pass `--rollback-on-failure`: a failed migration restores the backup ([If migration fails](../admin/upgrade.mdx#if-migration-fails)). Wait for the migration Job and the server.
4. **Phase 4, restore.** Run `helm upgrade` with the recorded `worker.replicas`. The server consumes the deliveries that the receiver published meanwhile.
5. Check Configuration readiness. Run one practice review and one Heph turn.

**Production is on k3s before 1.0 ships.** The 1.0 release, which deletes Compose self-hosting, ships only after hephaestus-prod runs 0.95.x on the chart (release order R1–R5).

### In-flight work

- **Reviews.** In phase 1, workers drain for `worker.drainTimeoutSeconds`. A review that ends in the drain keeps its results. A review that the drain stops is requeued and spends no retry, as today.
  The 1.0 migration requeues every `review_job` that is still claimed. It clears the owner and the attempt fence, spends no retry, and keeps every admitted and staged result. This is needed because 1.0 has no `worker_registry` recovery (ADR 0068).
  Stored config snapshots of an older schema version get a new snapshot when the job runs again.
- **Heph turns.** A turn that runs at phase 1 ends. The 1.0 migration ends each unfinished turn with the visible state "Heph was updated before it could answer. Ask again." It bills only the measured spend. A turn is not run again silently (decision F-15).
- **Feedback composition and delivery.** These are rows. They continue after phase 4. Uncertain provider writes keep their receipts, so readback decides, as today.
- **JetStream.** The webhook role keeps its 0.95 version until phase 2 changes it. The 1.0 server must read every message that the 0.95 receiver published. The release-upgrade webhook leg proves this for 1.0, although the schema waiver turns the other previous-release legs off. With no backlog, the operator can also start with empty streams after the drain to zero pending (ADR 0041).

### Schema step

- **0.95.0** is itself a stop-every-role release. It drops the contractions that v0.94.7 lists (`consent_notice` and its trigger function, `product_survey_submission`, and `supports_reasoning`), together with `review_job.idempotency_key` and `outline_document_event`.
  Before it drops `product_survey_submission`, it copies every answer that `product_survey_participation` does not hold yet, so no answer is lost.
  It also drops `workspace_slug_history.redirect_expires_at`, `issue.review_snapshot_id` and `review_snapshot_digest`, and the Slack research columns.
  Before it drops the Slack research columns, it copies the true research opt-outs into `consent_decision` (decision F-12).
- **1.0** drops the objects that the ADR 0068 layers retire (`worker_registry`, `worker_token_denylist`). It also converts in-flight jobs and turns as described above.
  Each layer keeps its own changelog (one per branch). No released change set changes. `master.xml` stays append-only.
  The only head edit is the cut-point precondition. It follows the v0.77.4 precedent.
- **No re-baseline at 1.0.** The policy requires a major boundary *and* automated synchronization. Synchronization does not exist, and a manual `docker run … changeLogSyncToTag` does not fit a Helm-only product.
  The gain is small: one file instead of 57 on fresh installs, and about 5.6 MB of classpath.
  Synchronization marks change sets as run without running them, which can hide native-object drift.
  Revisit at 2.0, after the migrate run mode can synchronize.
- After 1.0 the pending-contractions table is empty, and the two-release rule applies again.

### Release note and migration notes

- `.changeset/release-1-0-0.md`, `"hephaestus": major`:
  > Hephaestus 1.0 is the first release with the 1.x upgrade guarantee: from 1.0, any 1.x release upgrades to any later 1.y release. Self-hosting is the Helm chart on Kubernetes or on one host with k3s. Docker Compose is for local development only. **Operators:** upgrade to 0.95 on the Helm chart first, then follow [Upgrade from 0.x to 1.0](https://docs.hephaestus.build/admin/upgrade-to-1-0). The upgrade stops Hephaestus for the migration.
- `.migration/release-1-0-0.md`:
  > `#### 🔴 Stop every role for the upgrade to 1.0, which removes what 0.x needed`
  > 1.0 refuses a database that has not run 0.95.0. A Compose install moves to the Helm chart at 0.95 first. Stop the server and the workers, then migrate with the 1.0 chart, as Database maintenance describes. Running reviews start again after the upgrade and keep their results. A Heph answer in progress ends, and the developer asks again. Each removed setting is listed below: remove it from your values.
- Each 1.0 layer's own fragment names its one operator step. It does not need its own stop-every-role heading, because the 1.0 entry waives the whole release.
- **0.95.0** carries one deprecation changeset that lists every key, value and endpoint that 1.0 removes (CO-3). Thus, the deprecation policy holds.

### Rules this upgrade overrides (once)

| Rule (quoted) | Source | For 0.x → 1.0 |
|---|---|---|
| "Release *N* runs correctly on the schema of release *N+1*." | `docs/contributor/database-migration.mdx` § Changelog immutability policy, rule 3 | Waived. The 1.0 stop-every-role entry turns the guard and the previous-release legs off for 1.0 only. |
| "A change that the previous release cannot run beside takes two releases." | `database-migration.mdx` § Expand and contract | Waived for 1.0. Objects that 0.95 lists are dropped as usual. |
| "10. The previous server, worker, and webhook server boot on the new schema, and the previous server claims a review." | ADR 0058 § Gates before v1.0.0 | Waived for the server and the worker. It still holds for the webhook NATS contract, and again from 1.0.1. |
| "The Docker sandbox driver stays as the driver for development and for the live suites. … A decision after 1.0 removes it or keeps it." | ADR 0058 § Supported deployment | Replaced by ADR 0068 decision D: it goes before 1.0. |
| "The deletion can follow 1.0, and its release notes say so." | ADR 0058 § Notice and removal | The Compose self-host deletion lands in 1.0, after the 0.95.0 tag. |
| "The new Pods wait for the migrated schema, and the previous release serves until they are ready." | `docs/admin/kubernetes.mdx` § Upgrades | Not for 1.0: the Database maintenance procedure applies. |
| "≥ v0.78.0 — The baseline is already synchronized. Follow the target release migration procedure" | `docs/admin/compatibility-policy.mdx` § Database baseline prerequisites | For 1.0, the source must be 0.95.x. |
| "The maintainer-approved v0.77.4 cut-point guard is a changelog-level precondition at the head, not a change to released changesets." | `database-migration.mdx` rule 2 | Not overridden. It is the precedent for the 0.95.0 cut-point (approved, decision F-17). |
| "Never edit, rename, or delete a file under `db/changelog/` that reached `main`. `master.xml` is append-only." | `AGENTS.md` § Where to change what | **Not overridden.** |
| "A future baseline requires both a major-version boundary and automated synchronization." | `database-migration.mdx` § Baseline release gates | Not used: 1.0 does not re-baseline. |

### Decisions F-1 to F-18 (accepted 2026-10-28)

The owning layer of each item records the detail.

| # | Decision | Outcome |
|---|---|---|
| F-1 | Pre-cutover practice history: evidence version 0 and the `LEGACY_*` reasons (S-1, RL-1). Keep a read-only view, or convert or delete it? | Keep the `LEGACY_*` reasons (no false history). Keep version-0 rows as a read-only "earlier review" view, and collapse the branches into one adapter. |
| F-2 | Legacy feedback link resolver (FD-1) | Keep it permanently. Posted links are not ours to break. |
| F-3 | Hotfixes after 0.95.0: is a `release/0.95` branch allowed while `main` carries 1.0 deletions? | Yes. Otherwise the Compose deletions must wait for the 1.0 tag. |
| F-4 | Retire `docs/db/archive/v0.77.4/`, the archive tests and `WorkspaceConnectionBackfillChange` at 1.0 (needs a change to the immutability guard)? | Yes, with FU-2. 0.95 owns that path. |
| F-5 | Shared GitLab webhook endpoint for operator-created hooks (II-8) | Remove it. Re-register connection hooks at the cutover. |
| F-6 | Workspace subdomains on the chart, or unsupported in 1.0 (KR-6)? | Port to the chart before the Compose deletion. ADR 0053 is shipped behaviour. |
| F-7 | Maintenance page during the stopped upgrade (KR-13) | Delete. The server is down for minutes only. |
| F-8 | One at-rest encryption scheme; refuse or convert plaintext rows (RH-7) | Convert once in 1.0, then refuse. |
| F-9 | GitHub Discussions and Projects V2 sync, about 11k lines, unread (RE-4) | Delete, and drop the tables. Less stored person data. |
| F-10 | `NATS_ENABLED` as a setting, or derived from the role (RR-2) | Derive it from the role. |
| F-11 | Stored vs shown review status (RL-2) | Fold it into the lease layer. |
| F-12 | `slack_participant_consent.research_opted_out`/`source` hold opt-outs that v0.90 wrote | Carry true values into `consent_decision`, then drop. |
| F-13 | `GITLAB_WORKSPACE_CREATION`: GA or a permanent switch (FU-9) | GA. |
| F-14 | 22 public operations no client calls (API-6) | Keep only the ones a 1.0 UI or the documented API needs. Delete the rest in 1.0. |
| F-15 | A Heph turn in flight at the cutover: end visibly, or run again? | End visibly ("Ask again"). Never answer late without the developer asking. |
| F-16 | Skipping 1.x releases vs the one-step rolling rule (ADR § Consequences) | A skip upgrade stops every role. Say so in the compatibility policy. |
| F-17 | Approve the 0.95.0 cut-point edit at the head of `master.xml` (FU-2), as for v0.77.4 | Approve. |
| F-18 | `HEPHAESTUS_WORKER_CAPACITY_*` names (AR-14): rename in 1.0 with PR 6's persona decision | One name, "Heph turns", in the Heph loop layer. |

## Consequences

- One upgrade outage for every installation, bounded by the drain and the migration.
- A 0.x database older than 0.95.0 takes two hops.
- About 15k lines go: the Docker backend, the hub, the Heph host, the Compose stacks and gates, and the Compose host scripts. The unused Discussions and Projects sync goes too (decision F-9).
- Local development runs sandboxes on kind or k3d.
- **Gap to close with this ADR:** `compatibility-policy.mdx:69` promises "any 1.x to any later 1.y without intermediate releases". The guard and the previous-release check test only against the latest tag. Thus, a drop staged in 1.1 and run in 1.2 breaks a rolling 1.0 → 1.2 upgrade. Decided (F-16): an upgrade that skips a 1.x release stops every role. The compatibility policy says so.

---

## Revisit trigger

None for 1.0. The rolling rules apply again from 1.0.0. Revisit a schema re-baseline at 2.0, after the migrate run mode can synchronize.

## Update — 2026-10-28: lease recovery requeues the claimed reviews

This update amends § In-flight work and § Schema step for the reviews.

- The 1.0 changelog counts one drain on each running `review_job` without a lease. 0.x never set a lease, so these are the reviews that 0.x claimed.
- The server's lease recovery then requeues each of them. It clears the owner, raises the attempt fence and keeps every admitted and staged result.
  The drain offsets the new attempt number, so no retry is spent.
- SQL alone does not requeue them, because recovery also bills the attempt's measured spend and hands an admitted review on to compose. Both are steps of the server, which SQL cannot do.
- Recovery needs the server role with `AGENT_ENABLED=true`, as orphan recovery always has. It requeues these reviews within a minute of the server's start.
- `worker_registry` stays until the layer that removes the Heph tools sandboxes of a worker, its last reader. That layer drops it.
