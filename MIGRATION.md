# Migration Guide

This document helps you upgrade between versions of Hephaestus. For what a version number promises
(public contract, upgrade guarantee, support statement), see the
[Compatibility Policy](https://docs.hephaestus.build/admin/compatibility-policy).

> ⚠️ **Pre-1.0 Notice**: We are in active development. Minor versions (0.x.0) may contain breaking changes. Always test in staging before production.

## Quick Reference

| Symbol | Meaning |
|--------|---------|
| 🔴 | **Breaking**: Action required before upgrade |
| 🟡 | **Deprecated**: Works now. Removed in a later release — the release notes say which |
| 🟢 | **New**: No action needed |

## Check Your Version

For the running version, follow the
[runtime identity handoff](https://docs.hephaestus.build/admin/release-image-lock#runtime-identity-handoff).
It explains how to check each runtime role against the signed release lock.
The administration overview shows the server's identity.

```bash
# Latest published release
curl -fsSL https://api.github.com/repos/hephaestus-build/Hephaestus/releases/latest \
  | grep -m1 '"tag_name"'
```

Signed in, you can also read the running version straight off the app: production shows it in the
header, linked to its release notes.

---

## Pre-1.0 Development (Current)

During pre-1.0, we follow [Semantic Versioning 0.x conventions](https://semver.org/#spec-item-4):

> Major version zero (0.y.z) is for initial development. Anything MAY change at any time.

### What This Means

| Version Bump | May Contain |
|--------------|-------------|
| `0.x.0` → `0.y.0` | Breaking changes |
| `0.x.y` → `0.x.z` | Bug and security fixes |

### Upgrade Checklist

Before upgrading to any new `0.x.0` version:

1. ✅ Read the [release notes](https://github.com/hephaestus-build/Hephaestus/releases)
2. ✅ Check this migration guide for breaking changes
3. ✅ Verify the upgrade in staging first
4. ✅ Approve production deployment after staging verification

---

## Version History

Entries exist only for releases that need operator action. Everything else is in the
[release notes](https://github.com/hephaestus-build/Hephaestus/releases).

### Next release

### v1.0.0-rc.7

#### 🔴 Stop every role before the provider resource repair upgrade

1. Stop every server before the migration and deploy the matching release afterward.
   The previous server cannot write the new required authorization binding columns.
2. Finish connection authorization flows before upgrading, or start them again afterward.
   The migration discards pending OAuth nonces so each new authorization binds an exact connection lifecycle.
3. Open **Instance administration → Provider removals** after the upgrade.
   Inspect unresolved resources, including registrations whose outcome is unknown and removals with no surviving workspace.
4. Confirm at the provider that the old resource and uncertain operation are gone before releasing its block.
   A timeout or a missing resource alone is not confirmation that an earlier DELETE cannot still finish.
   Pending removals and suspended hooks without a capture receipt stay uncertain across this upgrade.
5. For a connection with a surviving workspace, open its integration administration page to release the exact block.
   Only an instance admin can do this.
6. Keep the identifiers listed under **Security retention** in the personal-data map within your security-retention policy.
   Credential erasure deadlines do not extend to preserve cleanup access.

Replace `integration_provider_teardown_total` in custom dashboards with `integration_provider_repair_attempts_total` and `integration_provider_repair_open`.
Route `HephaestusProviderRemovalNeedsConfirmation` to an instance administrator.

While repairs remain unresolved, or after a provider release is audited, rollback refuses to remove that evidence. Keep the matching release and apply a forward fix.

### v1.0.0-rc.6

#### 🔴 Stop every role before moving review leases to agent runs

The review leases move from `review_job` to the new `agent_run` table.
The previous release cannot read or renew a lease in the new table.

1. Stop the server, worker, and webhook roles.
2. Back up the database.
3. Upgrade. The migration gives each review job its run.
   A worker that stops normally hands its reviews back to the queue first.
   A review that a worker still held when it stopped is a lost attempt: after the upgrade, the server records its usage and queues it again, or fails it at its retry limit.
4. Start the roles.

Until the review sandbox renews its own run (a later 1.0 change), only the worker that started a review renews its lease.
If that worker stops, the review's running sandbox loses its lease and the review is queued again, instead of finishing on its own.
This is an intermediate state of the 1.0 development, not the 1.0 behaviour.

If you set `hephaestus.agent.max-retries` directly, set `hephaestus.agent.capacity.review-retries` instead. `AGENT_MAX_RETRIES` keeps working.
If the upgrade stops, restore the verified backup before you start the previous release.

#### 🔴 Use the canonical webhook receiver base

**Who is affected:** Operators whose previous GitLab or Outline receiver base differs from the API issuer origin must update their configuration. Compare the full base URL, including any path prefix. This includes explicit overrides and the previous implicit `hephaestus.host-url` default.

**Action:** Remove `WEBHOOK_EXTERNAL_URL` and `hephaestus.webhook.external-url`. Registration uses the API issuer origin, which is the chart host. Receiver paths begin with `/webhooks/`. They do not include `/api` or another proxy prefix.

For example, a previous base of `https://api.example.test/hooks` differs from the canonical base `https://api.example.test`, even though their origins match.

If the previous full GitLab receiver base differs:

1. Confirm that the canonical base routes `/webhooks/` to this installation's receiver.
2. Stop the application roles through the deployment runbook before changing GitLab registrations.
3. Record this installation's GitLab group hooks at the previous base, with their workspace and connection associations.
4. Remove only those recorded group hooks. Do not remove another installation's registration.
5. Remove the retired settings, upgrade, and start the roles.
6. Run full GitLab workspace synchronization for each affected connection to recreate registrations at the canonical base.
7. Verify provider delivery and confirm that the recorded hooks at the previous base are gone.

If delivery cannot be verified, stop the new roles. Restore the previous release and configuration. Recreate the recorded GitLab hooks.

If the full GitLab receiver base already matches, remove the settings and upgrade without manual hook removal. GitLab leaves hooks at another base in place, so the cleanup above is required even for a different path on the same origin.

Outline needs no manual subscription removal or separate stop step. After upgrading, run Outline collection synchronization for each affected connection. It replaces subscriptions whose full address differs and removes the previous subscription. Replacement subscriptions can have new route IDs and signing credentials.

GitHub App webhook registration still uses `/webhooks/github`. Slack keeps its Events API and separately routed interactivity addresses.

#### 🔴 Convert chart list values to arrays

Before upgrading, change these values in your site file to YAML arrays:

- `config.auth.bootstrapAdmins`
- `config.auth.browserExtensionIds`
- `config.auth.passkeys.allowedOrigins`
- `config.outline.allowedOrigins`

Use `[]` in place of an empty string. Split a comma-separated string into one array item for each value, in the same order:

```yaml
config:
  auth:
    bootstrapAdmins:
      - "github:1234567"
      - "gitlab:@example"
    browserExtensionIds: []
    passkeys:
      allowedOrigins: []
  outline:
    allowedOrigins: []
```

An array item cannot contain a comma. The chart rejects the old string form.

#### 🔴 Install the agent-sandbox controller before the chart

**Affected**: installations on a cluster other than the one-host k3s platform, with sandboxes enabled. The one-host installer applies the controller itself.

The chart now installs `SandboxTemplate` and `SandboxWarmPool` objects, which need the agent-sandbox controller and its resource definitions. Before you upgrade the chart, apply `k3s-agent-sandbox.yaml` from the release's k3s bundle, and wait for the controller:

```bash
kubectl apply --server-side -f k3s-agent-sandbox.yaml
kubectl -n agent-sandbox-system rollout status deployment/agent-sandbox-controller
```

Without it, `helm upgrade` stops with an error that it cannot find the kind `SandboxTemplate`.

#### 🔴 Rebuild custom agent images

If you use a custom agent image, rebuild it from the updated `docker/agents/pi/Dockerfile` before you upgrade the server. The runtime requires a compiled JavaScript toolkit and program child. An older image with TypeScript toolkit files does not meet this contract.

Official releases include matching images. Custom practice programs can still use TypeScript and do not need a source change.

#### 🔴 Stop every role for the conversation loop

Every conversation turn now runs in the conversation loop, a Deployment beside the server.
A worker of an earlier release cannot serve the turns of this release.

**Operators:**

1. Stop every role, as phase 1 of [Stopped schema upgrades](https://docs.hephaestus.build/admin/kubernetes#stopped-schema-upgrades) describes.
2. Before phase 2, add `HEPHAESTUS_CONVERSATION_LOOP_SECRET` to the release Secret.
   Run `hephaestus-keys.sh` from this release with your usual options. It adds only the keys that the Secret lacks.
   Or set the key yourself to 64 lowercase hexadecimal characters, the output of `openssl rand -hex 32`.
3. In your values, move `worker.conversationTurns` to `conversationLoop.turns`. The chart refuses `worker.conversationTurns`.
   The count is now the turns of the whole installation, not of each worker or server.
4. If you run the image outside the chart, remove `HEPHAESTUS_WORKER_CAPACITY_MENTOR_MAX`.
   Set `HEPHAESTUS_CONVERSATION_LOOP_SECRET` and `HEPHAESTUS_MENTOR_TURN_CAPACITY` on each server with `AGENT_ENABLED=true`. A server without the key hands out no conversation turn.
5. Continue with phase 2 of the procedure.

#### 🔴 Stop every role before moving conversation turns to sandboxes

Stop the old conversation loop before the database migration. Drain and stop all servers, then stop the webhook role. Remove the old sandbox claims and tools Jobs. Keep PostgreSQL available until the drain ends.

1. Scale `<release>-conversation-loop` to zero and wait for its Pods to stop. Do this before upgrading the chart, which removes that Deployment.
2. Stop the servers through their normal drain. Wait until every server Pod has stopped, then stop the webhook role.
3. In this release's dedicated sandbox namespace, foreground-delete claims with `hephaestus.build/sandbox-id` and legacy Jobs with `app.kubernetes.io/part-of=hephaestus`. Wait until all sandbox Pods are gone.
4. Rename `conversationLoop.turns` to `sandbox.conversationTurns`. Remove `conversationLoop.replicas` and `conversationLoop.resources`. Remove `conversationLoop` from every values file after the rename.
5. Remove `sandbox.runtime.runtimeClassName`, `sandbox.runtime.seccompProfile`, `sandbox.runtime.probeInterval`, and `sandbox.runtime.podOverhead`. The template runtime and overhead use `sandbox.templates.runtimeClassName` and `sandbox.templates.podOverhead`; its seccomp profile is `RuntimeDefault`.
6. Remove `HEPHAESTUS_CONVERSATION_LOOP_SECRET` from the application Secret and local environment. Remove `SANDBOX_STOP_TIMEOUT` and `SANDBOX_RECONCILIATION_INTERVAL`; SandboxClaims and the common reaper own that lifecycle. No replacement loop credential is needed. Each sandbox receives its own subject-scoped token.
7. Apply the new chart and run `node scripts/kubernetes-cluster.ts verify`, or run `scripts/sandbox-isolation.ts` against each template. Read [Sandbox prerequisites](https://docs.hephaestus.build/admin/sandboxes#sandbox-prerequisites) before enabling work.

The migration drops `conversation_tools_pod`. Context-load admission still uses `mentor_turn.tools_loads`; that live limit remains.
The server's sandbox Role now permits only `get`, `list`, `watch`, `create`, and `delete` on SandboxClaims. The controller owns Pods and their lifecycle.

The old startup isolation probe is removed. Server readiness does not qualify sandbox isolation. The platform gate must pass for each eligible sandbox node.


The stopped-major reconciliation check removes owned claims and legacy Jobs only after servers stop. A same-major upgrade still requires the explicit stop procedure above.

If `sandbox.conversationTurns` is `auto`, supply `sandbox.budget.cpu` and `sandbox.budget.memory` before upgrading. The small profile already declares its existing allocatable budget. Managed clusters have no default: the operator must enter the release's allocatable aggregate. With an explicit count, the budget is optional; when supplied, it validates steady state and the drained-upgrade peak. A count that does not fit fails chart validation. Finish reviews before the upgrade's transient peak and resume them only after migration/stanza Jobs and replacement-role surge end. Read [Sandbox capacity budget](https://docs.hephaestus.build/admin/helm-chart#sandbox-capacity-budget).

#### 🔴 Stop conversation work before enabling digest runs

Drain and stop all server replicas before the database migration. Take a verified database backup. Deploy the matching server and agent images for runtime contract 19 together.

The migration permits `DIGEST` runs and real `SYSTEM` turn stimuli. Existing completed digest entries remain readable. New digests use the conversation runner and the same conversation capacity. No new pool or capacity value is required. A configured count of zero stops both turn and digest claims and dispatch.

Remove the stopped release's conversation SandboxClaims before resuming work, so each new sandbox uses the matching image. Run the sandbox isolation gate, then resume the servers. Keep weekly-digest opt-in unchanged; an account that did not opt in receives no digest.

Restore the verified backup to roll back this schema cutover. An older server cannot read the new run kind and turn surface. Do not delete digest rows to make a downgrade pass.

#### 🔴 Remove existing sandbox claims before starting the new workers

Stop the workers, then remove their remaining sandbox workloads with foreground deletion before you start the new workers.
Follow steps 3 and 4 of the [Planned node maintenance procedure](https://docs.hephaestus.build/admin/maintenance#planned-node-maintenance).
Use the release's own sandbox namespace.
Existing claims keep their old expiry policy.
Every claim created by the new workers uses `DeleteForeground`.
Do not proceed while sandbox Pods remain.

#### 🔴 Choose the feedback retention before you upgrade

Feedback and practice results now share the conversation retention: 400 days unless you set another value.
The first daily run after the upgrade, at 03:00 server time, erases every feedback and every result older than that.
The erasure cannot be undone.

1. Decide how long your instance keeps what Hephaestus says about a person. The value is at least `P15D`.
2. On Helm, rename `config.conversationRetention` to `config.feedbackRetention` if you set it. The chart rejects the old name.
   If you did not set it, set `config.feedbackRetention` only to keep data longer than 400 days, for example `P730D`.
3. Outside the chart, the environment variable is `HEPHAESTUS_FEEDBACK_RETENTION`. The server refuses to start while `HEPHAESTUS_MENTOR_CONVERSATION_RETENTION` is still set.
4. Upgrade.

State the period in your privacy notice: [Data retention](https://docs.hephaestus.build/admin/data-retention) lists what it covers.

#### 🔴 Remove broker consumer settings

Remove `config.durableConsumerName` and `config.consumerInactiveThreshold` from every values file. The chart refuses both keys, including their old defaults.

Provider events and replay now use the PostgreSQL inbox and its scope leases. Complete the migration entry *Drain the webhook streams before the upgrade* before starting the target server. There are no broker consumer names or acknowledgment positions to configure in the new release.

#### 🔴 Decide whether GitHub App installations need an instance admin's approval

Before 1.0, each new GitHub App installation created its own workspace, whatever the workspace creation policy.
Now an installation follows the policy ([Who creates the workspace of an installation](https://docs.hephaestus.build/admin/github-integration#who-creates-the-workspace-of-an-installation)).
Under the default `ADMIN_ONLY`, a new installation waits in **Instance administration → GitHub installations** until an instance admin adopts it.
Existing workspaces and their installations are unaffected.

**Operators:** to keep App installations creating workspaces without approval, set `config.workspaceCreationPolicy: SELF_SERVICE`.
It also opens `POST /workspaces` to every signed-in user.
Otherwise, no action is necessary.

#### 🔴 GitHub synchronization is incremental

Remove `HEPHAESTUS_SYNC_INCREMENTAL_ENABLED` and `hephaestus.integration.github.sync.incremental-sync-enabled` from your configuration.

The update watermark still includes the configured `HEPHAESTUS_SYNC_INCREMENTAL_BUFFER` overlap. The first sync still uses `hephaestus.sync.timeframe-days`. Historical backfill continues to read older work with its own checkpoints and limits.

#### 🔴 Refresh the internal PostgreSQL certificate

The standard one-host certificate manifest now requests a ten-year PostgreSQL server certificate, matching its CA.
After applying the updated platform manifest, wait for cert-manager to issue the replacement certificate.
Check the issued expiry with `kubectl --namespace hephaestus get certificate hephaestus-postgresql-tls -o jsonpath='{.status.notAfter}'`.
Then restart PostgreSQL with `kubectl --namespace hephaestus rollout restart statefulset/hephaestus-postgresql`.
Wait for PostgreSQL and the application roles to become ready before closing the maintenance window.
Follow [TLS certificates](https://docs.hephaestus.build/admin/secret-custody#tls-certificates) for certificate changes.

#### 🔴 Stop every role before correcting unknown issue comment counts

An omitted provider comment count used to be stored as zero. The migration makes issue comment counts nullable and changes existing issue zeros to unknown. It preserves positive counts and every pull request count. Counts show as unknown until the next backfill.

1. Stop every runtime role and take a verified database backup.
2. Run the upgraded migration job.
3. Start only the upgraded roles. The previous server maps the count to a primitive and cannot read the corrected rows.
4. Ask the administrators of each affected workspace to run a backfill.
   In the workspace's integration settings, they select its GitHub or GitLab connection and select **Run backfill** ([Repair activity history](https://docs.hephaestus.build/admin/activity-history-repair#run-the-repair)).
   Until it finishes, these issues show an unknown comment count.
   After it finishes, request a new review for work whose evidence was incomplete. Existing results are not rewritten.

To roll back, stop every role and restore the verified backup from before this migration before starting the previous server. Do not apply a reverse update that turns unknown counts into zero.

#### 🔴 Give each database purpose its own login

Before 1.0, the server, the worker, the migration Job and the bundled PostgreSQL shared one login: the PostgreSQL superuser in `DATABASE_USERNAME` and `DATABASE_PASSWORD`.
Now each purpose has its own role ([Database roles](https://docs.hephaestus.build/admin/kubernetes#database-roles)):

- The server logs in as `hephaestus_app`, with `DATABASE_APP_PASSWORD`. It reads and writes rows only.
- The migration Job logs in as `hephaestus_owner`, with `DATABASE_OWNER_PASSWORD`. It owns the database and its schema objects.
- The administrator of the bundled PostgreSQL moves to `DATABASE_ADMIN_USERNAME` and `DATABASE_ADMIN_PASSWORD`. No Hephaestus process logs in with it.

The chart's `files/database-roles.sql` creates both roles and moves the existing schema objects to `hephaestus_owner`.
`REASSIGN OWNED BY` cannot do this for the bundled PostgreSQL: it refuses the bootstrap superuser, which owns the system catalogs.
`helm upgrade` stops while a key is missing, or while the Secret still holds `DATABASE_USERNAME` or `DATABASE_PASSWORD`.

**Operators:** do these steps with the [Database maintenance](https://docs.hephaestus.build/admin/kubernetes#database-maintenance) phases.
The steps use the namespace `hephaestus` and the Secret `hephaestus-app`.

1. Back up the database.
2. Do phase 1 with the chart and values of the current release: stop the server and the worker.
   The migration then moves the schema objects while nothing else holds a lock on them.
3. Change the Secret.
   - With the bundled PostgreSQL, move the administrator login to its new keys:

     ```bash
     kubectl -n hephaestus get secret hephaestus-app -o json \
       | jq '.data |= (. + {DATABASE_ADMIN_USERNAME: .DATABASE_USERNAME, DATABASE_ADMIN_PASSWORD: .DATABASE_PASSWORD} | del(.DATABASE_USERNAME, .DATABASE_PASSWORD))' \
       | kubectl replace -f -
     ```

     Then run `hephaestus-keys.sh` of the new release, as [Create the Secret](https://docs.hephaestus.build/admin/kubernetes#create-the-secret) describes.
     It adds `DATABASE_OWNER_PASSWORD` and `DATABASE_APP_PASSWORD`.
     The migration Job of the next revision creates both roles with these passwords.
   - With an external PostgreSQL, run `files/database-roles.sh` of the new release as your administrator, as [Database roles on an external PostgreSQL](https://docs.hephaestus.build/admin/kubernetes#database-roles-on-an-external-postgresql) describes.
     Then put `DATABASE_OWNER_PASSWORD` and `DATABASE_APP_PASSWORD` in the Secret, and remove `DATABASE_USERNAME` and `DATABASE_PASSWORD`.
4. Do phases 3 and 4 with the new release.
   The migration Job sets up the roles, moves the schema objects and migrates as `hephaestus_owner`.
   With the bundled PostgreSQL, PostgreSQL restarts once, because it reads its administrator from the new keys.
5. Check that the server connects as `hephaestus_app`:

   ```bash
   kubectl -n hephaestus exec hephaestus-postgresql-0 -c postgresql -- \
     sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT DISTINCT usename FROM pg_stat_activity WHERE application_name = '"'"'PostgreSQL JDBC Driver'"'"'"'
   ```

   It prints `hephaestus_app` only.
6. Update your copy of the keys outside the cluster: `DATABASE_ADMIN_USERNAME` replaces `DATABASE_USERNAME` ([Copy the keys from the Secret](https://docs.hephaestus.build/admin/secret-custody#copy-the-keys-from-the-secret)).
7. With an external PostgreSQL, remove the old login when nothing else uses it.

If you start the application image outside the chart, give the migrate run mode the login of `hephaestus_owner`, and the server the login of `hephaestus_app`.
Before each migration that changes a password, run `files/database-roles.sh` as the administrator.

To go back to the previous release, put `DATABASE_USERNAME` and `DATABASE_PASSWORD` back with the administrator's values.
The previous release logs in as the administrator again, so the moved objects need no change.

#### 🔴 Log context uses consistent field names

Update queries, saved searches, and collector rules that read these fields:

| Previous field | New field |
| --- | --- |
| `context.installation_id` | `context.installation.id` |
| `context.proxy.apiProtocol` | `context.proxy.api_protocol` |

Records inside an outbox attempt carry `context.action.id`, `context.action.kind`, and `context.action.attempt`.
The attempt count is distinct from a run's lease epoch, `context.run.epoch`.

#### 🔴 Log records name their trace in `trace.id` and `span.id`

A log record now carries the trace scope in the Elastic Common Schema fields `trace.id` and `span.id`, at the top level of the record.
The fields `context.traceId` and `context.spanId` are gone.

Update every query, saved search, collector rule and alert that reads them:

- A search for the reference that a reader reports: `grep '"trace":{"id":"<reference>'` instead of `grep '"traceId":"<reference>'`.
- An OpenTelemetry Collector `filelog` pipeline: read the trace ID from `attributes.trace.id` and the span ID from `attributes.span.id`. [Ship logs to a log store](https://docs.hephaestus.build/admin/monitoring#ship-logs-to-a-log-store) has the example.

#### 🔴 NATS broker certificate names

The broker is removed; follow [Remove the retired sync broker values before upgrading](https://docs.hephaestus.build/admin/upgrade#remove-the-retired-sync-broker-values-before-upgrading) and its complete removal list instead of the previous broker instructions.

#### 🔴 Use the stopped procedure for one-host release changes

The one-host installer now accepts only a first install or a reapply of the same successfully deployed release.
It refuses a different, failed, or pending release before it changes the host.

**Operators:** Follow [Stopped schema upgrades](https://docs.hephaestus.build/admin/kubernetes#stopped-schema-upgrades) for a release change.
Keep the current release files for the stop phase.
Verify the final restore point with all roles stopped before the target migration starts, without automatic rollback.
After a migration failure or refusal, restore that backup before you return to the old release.

#### 🔴 Use the small profile on every single-node install

The redundant `small-16gib` profile is removed. Before upgrading, set `HEPHAESTUS_CHART_PROFILE=small` if it contains `small small-16gib` or `small,small-16gib`. Remove `--values profiles/small-16gib.yaml` from Helm commands and keep `--values profiles/small.yaml`.

The change does not alter the capacity of the `small` profile.

A stale profile setting stops the reconciler with `the release has no chart profile small-16gib` before Helm runs. The previous release keeps serving. The installer selects `small` automatically, so a new install needs no extra step.

#### 🔴 Change the model call queries that read `class` or `queue`

Model calls now have a band, `interactive`, `batch` or `background`, in place of a call class.
The bundled alerts and dashboards read the new label. If you have your own queries, alerts or dashboards on these metrics, change them:

- `llm_pool_permits_active`, `llm_pool_permit_wait_seconds`, `llm_pool_permit_abandoned_total`, `llm_pool_permits_granted_total`, `gen_ai_client_operation_time_to_first_chunk_seconds`, `gen_ai_client_operation_duration_seconds`, `gen_ai_client_calls_total` and `gen_ai_client_tokens_total` have the label `band` in place of `class`.
  `class="turn"` becomes `band="interactive"`.
  `composition`, `request` and `event` become `band="batch"`.
  `schedule`, `backfill` and `product_feedback` become `band="background"`.
- `llm_pool_waiters` has the label `band` in place of `queue`.
  `queue="conversation"` becomes `band="interactive"`.
  `queue="background"` becomes `band=~"batch|background"`.

Series with the old labels stop at the upgrade.

#### 🔴 Let the one-host installer reach the gVisor download

**Affected**: one-host k3s installations. Other clusters are not affected.

The installer now downloads the pinned gVisor release from `storage.googleapis.com` and checks its SHA-512. Allow that address in the host's egress rules before you run the installer of this release. The first run with gVisor restarts k3s once, which stops the running Pods for a short time.

#### 🔴 Check practice identifiers before upgrading

New practices cannot use the occupied first segments of practice API routes. The creation APIs and editor enforce the same rule. Group names are not reserved by this change. Existing practice identifiers are immutable and are not renamed.

Run this read-only preflight with a database role that can read all workspaces. Both counts must be zero before upgrading. Do not proceed if a collision exists: prepare a reviewed data migration or refuse the upgrade.

```sql
BEGIN READ ONLY;
SET LOCAL statement_timeout = '5s';
SET LOCAL row_security = off;
WITH reserved(slug) AS (VALUES
    ('definition-options'),
    ('feedback'),
    ('feedback-modes'),
    ('items'),
    ('practice-script-needs'),
    ('releases'),
    ('results'),
    ('review-batches'),
    ('review-context'),
    ('review-requests'),
    ('review-schedules'),
    ('review-settings'),
    ('reviewed'),
    ('reviews'),
    ('scans'),
    ('standings'),
    ('tiles'),
    ('workspace-overview')
)
SELECT 'workspace_practices' AS scope, count(*) AS collisions
FROM practice JOIN reserved USING (slug)
UNION ALL
SELECT 'catalog_practices', count(*)
FROM catalog_entry JOIN reserved USING (slug)
WHERE entry_type = 'PRACTICE';
ROLLBACK;
```

The published input patterns in `server/openapi.yaml` define the rule. Future static practice APIs must use a separate namespace so they cannot take an identifier that this release accepts.

#### 🔴 Read problem responses from the top level

Before upgrading, update custom API clients to accept `application/problem+json` for error responses. Read `requestId`, `errors`, and other extension fields from the top level of the body. The generated model no longer declares a nested `properties` object.

CSRF denials use this problem response in both workspace address modes. On a 403 with type `https://docs.hephaestus.build/contributor/api-error-handling#csrf-token-rejected`, fetch a new CSRF token once, then retry the refused request once. Keep the existing handling for other 403 responses.

Regenerate clients from the OpenAPI specification.

#### 🔴 Product feedback drafts use server egress

Before upgrading, verify a database backup. This migration cannot be reversed. Restore that pre-upgrade backup to recover the previous schema and draft claims.

If your firewall limits server egress, allow the provider of the instance model selected for product feedback. Keep the agent runtime enabled on the server to draft reports. The automatic migration routes pending legacy drafts without a model answer, then removes their old delivery claims. Existing reports and filed issues remain available.

#### 🔴 Drain the webhook streams before the upgrade

Webhook deliveries now wait in the provider event inbox in PostgreSQL. The upgrade does not read the NATS webhook streams, and it drops the old dead-letter records, which held no payload.

**Affected**: every installation that receives provider webhooks.

**Operators:**

1. Replay the dead letters that you want to keep, with the version that you run now ([Replay a failed webhook event](https://docs.hephaestus.build/admin/webhook-replay)).
   A dead letter that you do not replay is not carried over. The routine sync repairs what it can.
2. Just before you stop the roles, wait until `webhook_stream_consumer_pending` and `webhook_stream_consumer_ack_pending` are 0 for each stream.
   An event that is still in a stream when the roles stop is not handled.
3. Stop every role, as phase 1 of [Database maintenance](https://docs.hephaestus.build/admin/maintenance#database-maintenance) describes.
   While no server runs, GitHub redelivers the failed deliveries on its bounded redelivery schedule after the upgrade, Slack retries, and the routine sync repairs GitLab and Outline.
4. In your values, remove `webhook` (its last setting was `webhook.stream`) and `config.consumerInactiveThreshold`.
   Outside the chart, remove the `HEPHAESTUS_WEBHOOK_STREAM_*` variables and `HEPHAESTUS_INTEGRATION_CONSUMER_INACTIVE_THRESHOLD`.
5. In your own alert routing, remove `HephaestusStreamPollStale`, `HephaestusStreamPollMissing`, `HephaestusStreamBacklog`, `HephaestusStreamConsumerStopped`, `HephaestusWebhookStreamPastAgeBound` and `HephaestusWebhookRetentionBelowAgeBound`.
   Add `HephaestusProviderEventsWaiting` and `HephaestusProviderEventBacklogUnsampled`.
   Replace `integration_consumer_nak_total` with `integration_consumer_retry_total` in your own rules and dashboards.
6. Continue with phase 2 of the procedure.
   The inbox keeps handled deliveries for 3 days and dead letters for 30 days: plan PostgreSQL space for 3 days of webhook traffic.
   The migration drops the old inbox and dead-letter tables. To go back to the earlier version, restore a PostgreSQL backup taken before the upgrade ([Backup and restore](https://docs.hephaestus.build/admin/backup-restore)).

#### 🔴 External NATS broker: NATS Server 2.11 or later

The broker is removed; follow [Remove the retired sync broker values before upgrading](https://docs.hephaestus.build/admin/upgrade#remove-the-retired-sync-broker-values-before-upgrading) and its complete removal list instead of the previous broker instructions.

#### 🔴 Public activity pages count contributors without an account instead of naming them

After the upgrade, a published public activity page no longer names contributors who have no Hephaestus account that chose to show. It shows their number instead, as "and N more contributors". To keep naming them, turn on **Show contributors without an account** in the workspace's public activity page settings. Consider the legal basis first: your record of processing should state why you publish people who have not chosen it.

API clients that call `PATCH /workspaces/{workspaceSlug}/public-activity` send only the settings that they change. Omitted or null fields stay unchanged. Turning the page off resets the other two settings.

#### 🔴 Stop every role to cut over to 1.0.0

1.0.0 has no mixed-version window: no role of the previous release runs beside a 1.0.0 role or on its schema.
Follow [Upgrade to 1.0](https://docs.hephaestus.build/admin/upgrade-to-1-0).
It lists the prerequisites (the agent-sandbox controller, the sandbox runtime, NetworkPolicy enforcement ), the values to move, and the stopped cutover in order.
Read every other entry of this section: each owns one change.
To go back, restore the verified backup from before the migration, then install the previous release.

#### 🔴 Stop every role before the models for practice scripts are deleted

Practice scripts make no direct model call any more, so the upgrade deletes what served those calls.

1. Note the instance connections that use the Decisions, Embeddings or Rerank API, if you want to keep their settings elsewhere. The upgrade deletes them, their models, and the decision, embedding and reranking assignments of every workspace.
2. Stop the server, worker, and webhook roles.
3. Back up the database.
4. Upgrade. The migration also rewrites the frozen review method of each review and the snapshot of each queued job to the new shape.
5. Remove `PRACTICE_REVIEW_DIRECT_MODEL_TOKENS_PER_ATTEMPT` and `PRACTICE_REVIEW_DIRECT_MODEL_SEARCH_UNITS_PER_ATTEMPT` from your configuration if you set them.
6. Start the roles.

Past usage of these models stays in **AI usage**. If the upgrade stops, restore the verified backup before you start the previous release.

#### 🔴 Remove the retired sync broker values before upgrading

Sync-status hints now use PostgreSQL notifications. The release runs no NATS broker and opens no broker connection.

Remove `nats` from every values file, including all profile and environment overrides. This removes these retired keys:

- Remove `nats`.
- Remove `nats.bundled`.
- Remove `nats.maxPayloadBytes`.
- Remove `nats.jetstream`.
- Remove `nats.jetstream.maxMemory`.
- Remove `nats.jetstream.maxFileBytes`.
- Remove `nats.persistence`.
- Remove `nats.persistence.storageClassName`.
- Remove `nats.resources`.
- Remove `nats.external`.
- Remove `nats.external.url`.
- Remove `nats.external.cidr`.
- Remove `nats.external.port`.
- Remove `nats.resources.requests.cpu`.
- Remove `nats.resources.requests.memory`.
- Remove `nats.resources.requests.ephemeral-storage`.
- Remove `nats.resources.limits.cpu`.
- Remove `nats.resources.limits.memory`.
- Remove `nats.resources.limits.ephemeral-storage`.
- Remove `nats.resources.requests`.
- Remove `nats.resources.limits`.

Remove `images.nats` from every image values file. The chart refuses these retired keys; it does not ignore them.
Regenerate image values from the new release lock instead of reusing the previous image map.

Remove every `hephaestus.sync.nats.*` property and the `NATS_SERVER`, `NATS_USERNAME`, `NATS_PASSWORD`, `NATS_DURABLE_CONSUMER_NAME`, `NATS_ENABLED` and `NATS_PORT` environment settings.
Stop passing `--external-nats` to the Secret helper and remove its unused broker keys from your release Secret.
After the server upgrade, confirm sync SSE updates and the REST polling fallback. Then stop any external broker used only by this release.
A Helm upgrade removes the bundled workload. Its old PersistentVolumeClaim can remain; identify that release's exact claim and remove it only after confirming that no other application uses its data.

The chart budgets five dedicated PostgreSQL listeners per server, including old servers during rolling overlap.
Use the chart's derived pool and `max_connections`; keep the existing profile budgets and sandbox template requests.

#### 🔴 Remove the Git checkout switch

**Who is affected:** Operators whose chart values or retained `site.yaml` contain `config.gitCheckoutEnabled`.

**Action:** Remove `config.gitCheckoutEnabled` from each values file before you upgrade. The chart refuses the retired key, for both `true` and `false`.

**Behavior:** The server and worker always enable Git checkout. Practice reviews need the repository at its reviewed revision. The webhook role receives no checkout setting.

#### 🔴 Remove the review poll settings and the review Job metrics

Workers no longer run practice reviews. Each review runs in its own agent sandbox.
Remove `AGENT_POLL_INTERVAL` and `AGENT_CLAIM_BATCH_SIZE` from your configuration. They have no effect.
Remove dashboards and alerts of your own that read `sandbox_containers_active`, `sandbox_executions_total`, `sandbox_execution_duration_seconds`, `sandbox_start_failures_total`, `sandbox_review_starved_seconds`, `sandbox_cleanup_failures_total`, `sandbox_reconciler_*`, `worker_capacity_*`, `worker_drain_active`, `agent_job_drained_total` or `agent_job_execution_duration_seconds`. These metrics are no longer exported.

#### 🔴 Rebuild a custom agent image

**Affected**: deployments that pin their own agent image. An image from the release runs without any action.

A review Pod now has more than one container, and they start from programs in the image. Rebuild your image from this release, then verify that its `hephaestus.agent.runtime-contract` label matches the official agent image for this release.

#### 🔴 Use a root HTTPS API issuer in production

**Who is affected:** Every production server whose `hephaestus.auth.issuer` is absent, uses HTTP, or contains a path, credentials, query or fragment. Chart installations already supply a root HTTPS issuer.

**Action:** Before upgrading, set `HEPHAESTUS_AUTH_ISSUER` or `hephaestus.auth.issuer` to the public API root origin, for example `https://api.example.test`. Keep a public API prefix in `hephaestus.auth.api-base-path`, not in the issuer. The production server refuses startup until the issuer passes this check, even when GitLab, Outline and Slack are disabled.

Changing the issuer changes the JWT `iss` claim. Existing access tokens are rejected, so users must sign in again. Verify API routing, then register the canonical Slack callback before upgrading if Slack connections are enabled. A process that runs only the webhook or worker role does not require this issuer check.

#### 🔴 Update custom practice script imports

Before upgrading, check custom scripts for these removed imports:

- `@hephaestus/practice-scripts/models`
- `threadRecordSchema` from `@hephaestus/practice-scripts/chat`
- `documentRecordSchema` from `@hephaestus/practice-scripts/docs`

Use `chat(ctx)` and `docs(ctx)` for domain records. Keep `meta` empty, prepare evidence with the public helpers, and call `ctx.review`. Set the review model and budget in the workspace. Validate each updated script before enabling it.

The four supported entries are `@hephaestus/practice-scripts`, `/scm`, `/chat`, and `/docs`. Private relative imports and removed entry points are rejected.

#### 🔴 Route provider webhooks to the server

The server now receives provider webhooks. The chart has no webhook Deployment, and it refuses `webhook.replicas` and `webhook.resources`.

**Affected**: every Kubernetes installation, and every installation that starts the application image outside the chart with the `webhook` Spring profile or `HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED`.

**Operators:**

1. Stop every role, as phase 1 of [Stopped schema upgrades](https://docs.hephaestus.build/admin/upgrade#stopped-schema-upgrades) describes.
   The 1.0 upgrade stops every role in any case.
2. In your values, remove `webhook.replicas` and `webhook.resources`. Keep `webhook.stream`.
   The webhook Pod's memory is free for the server. Keep the server's defaults or add it to `server.resources`.
3. Outside the chart, stop the webhook container. Remove `HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED` and the `webhook` Spring profile.
   Route `/webhooks` and every path below it to the server.
4. Replace `HephaestusWebhookReceiverDown` and `HephaestusWebhookReceiverUnready` in your own alert routing with `HephaestusServerDown` and `HephaestusServerUnready`.
5. Remove filters on `runtime.role` from your log queries. Logs now identify the one application through its service metadata.
6. Continue with phase 2 of the procedure.
   The upgrade deletes the webhook Deployment, its Service, its ServiceAccount, its disruption budget and its network policy.

#### 🔴 Reserve room for rolling server updates

Remove `server.rolloutStrategy` from your site values. The chart refuses this retired key and always uses `RollingUpdate`.

Use the target release's profile. The default is two steady servers; `small` keeps one. Budget space and database connections for every draining server beside every replacement, as [Sandbox capacity budget](https://docs.hephaestus.build/admin/helm-chart#sandbox-capacity-budget) requires. Set `server.databasePool.maximumPoolSize` to `auto`, or recalculate an explicit count against the runtime holder floor. Remove a copied `postgresql.parameters.max_connections: 80` override: the target small profile derives 120. It keeps the PostgreSQL memory request and limit at 2 GiB. [Database connections](https://docs.hephaestus.build/admin/sizing#database-connections) gives the pool and client breakdown.

Finish the review drain before the upgrade peak, as [Sandbox capacity during upgrade](https://docs.hephaestus.build/admin/upgrade#sandbox-capacity-during-upgrade) requires. Start no further upgrade until all old server Pods have stopped. A schema-compatible release can then roll while a server remains ready.

The initial cutover to 1.0 and every incompatible schema still use the stopped procedure in [Upgrade to 1.0](https://docs.hephaestus.build/admin/upgrade-to-1-0).

#### 🔴 Move the worker values to the server and the sandboxes

The server now runs the sandboxes, their gateway and the model proxy. The chart has no worker Deployment, and it refuses every `worker` value.

**Affected**: every Kubernetes installation, and every installation that starts the application image outside the chart with `HEPHAESTUS_RUNTIME_WORKER_ENABLED`.

**Operators:**

1. Stop every role, as phase 1 of [Stopped schema upgrades](https://docs.hephaestus.build/admin/upgrade#stopped-schema-upgrades) describes.
   The 1.0 upgrade stops every role in any case.
2. In your values, move the keys:

   | Before                                                                                                               | After                                                                             |
   | -------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
   | `worker.enabled`                                                                                                     | `sandbox.enabled`                                                                 |
   | `worker.sandbox.namespace`, `worker.sandbox.reviewMemory`, `worker.sandbox.quota`, `worker.sandbox.templates`, `worker.sandbox.controller`        | `sandbox.namespace`, `sandbox.reviewMemory`, `sandbox.quota`, `sandbox.templates`, `sandbox.controller` |
   | `worker.runtime`                                                                                                     | `sandbox.runtime`                                                                 |
   | `worker.sandboxGateway.maxConnections`                                                                               | `sandbox.gateway.maxConnections`                                                  |
   | `networkPolicy.workerGatewayPorts`                                                                                   | `networkPolicy.modelGatewayPorts`                                                 |
   | `worker.drainTimeoutSeconds`, `worker.databasePool`, `worker.gitCache`, `worker.resources` | The same keys under `server`                                                      |

   Remove `worker.replicas`. `server.replicas` counts the servers.
   Add the worker's memory and its git cache to the server's, or keep the server's new defaults.
   Remove `worker.rolloutStrategy`. [Helm chart](https://docs.hephaestus.build/admin/helm-chart) owns the server rollout and capacity rules.
   Keep a review drain window: `server.drainTimeoutSeconds` defaults to 1800, or 600 in the small profile.
   The Pod grace is the drain plus two minutes; the Helm timeout must also cover the new Pod's start.

3. Outside the chart, remove `HEPHAESTUS_RUNTIME_WORKER_ENABLED` and the `worker` Spring profile.
   Set `AGENT_ENABLED=true`, a valid `HEPHAESTUS_CONVERSATION_LOOP_SECRET` and the sandbox settings on the server instead.
   Replace `HEPHAESTUS_WORKER_DRAIN_TIMEOUT` with `HEPHAESTUS_AGENT_DRAIN_TIMEOUT`. Other `hephaestus.worker.*` settings are removed.
4. Continue with phase 2 of the procedure.
   The upgrade deletes the worker Deployment, its ServiceAccount, its Role and its network policies.

#### 🔴 Use the canonical Slack workspace callback

**Who is affected:** Operators with Slack workspace connections enabled.

**Action:** Register the configured API origin and public API prefix followed by `/oauth/callback/slack` as a Redirect URL on the Slack app. For a standard chart installation, this is `https://<host>/api/oauth/callback/slack`. Do not use a workspace subdomain.

Remove `config.slack.redirectUri` from every values file. Remove `HEPHAESTUS_INTEGRATION_SLACK_REDIRECT_URI` and `hephaestus.integration.slack.redirect-uri` from application configuration. The chart refuses the retired key. The application derives the callback and ignores a leftover `HEPHAESTUS_INTEGRATION_SLACK_REDIRECT_URI`.

Authorization and token exchange send the same callback address. If the registered address already matches it, no Slack app change is required. The Slack sign-in callback and interactivity Request URL keep their existing paths.

#### 🔴 Stop every role before replacing live Slack replies

Stop all server replicas and keep a verified pre-upgrade database backup before applying this migration. Do not roll old and new server versions together. The migration moves the existing Slack outcomes into the outbox and removes the entry delivery and stream fields. It cannot be reversed; restore the pre-upgrade backup to recover the previous schema.

Existing in-flight replies whose Slack effect is unknown remain unconfirmed and are not posted again. The full answer remains in the app. New Slack replies are posted once their complete answer passes admission. Use **Stop answer** in the app to stop an unfinished answer.

The `mentor_slack_reply_total` metric is removed. Use `action_ended_total{kind="SLACK_POST"}` and the outbox alerts instead. Old in-flight actions ended unconfirmed by the migration do not run a new feedback receipt; their feedback remains unconfirmed.

#### 🔴 Rename scheduled sync settings

Before upgrading, rename `config.monitoring` to `config.sync` in the site file. Keep `runOnStartup`, `timeframeDays`, `syncCron`, and `cooldownMinutes` and their current values.

Replace these explicit server environment variables if you set them outside the chart:

| Removed variable | Use instead |
| --- | --- |
| `MONITORING_RUN_ON_STARTUP` | `SYNC_RUN_ON_STARTUP` |
| `MONITORING_TIMEFRAME` | `SYNC_TIMEFRAME_DAYS` |
| `MONITORING_SYNC_CRON` | `SYNC_CRON` |
| `MONITORING_SYNC_COOLDOWN_IN_MINUTES` | `SYNC_COOLDOWN_MINUTES` |
| `MONITORING_BACKFILL_ENABLED` | `SYNC_BACKFILL_ENABLED` |
| `MONITORING_BACKFILL_BATCH_SIZE` | `SYNC_BACKFILL_BATCH_SIZE` |
| `MONITORING_BACKFILL_RATE_LIMIT_THRESHOLD` | `SYNC_BACKFILL_RATE_LIMIT_THRESHOLD` |
| `MONITORING_BACKFILL_INTERVAL_SECONDS` | `SYNC_BACKFILL_INTERVAL_SECONDS` |

The application property names under `hephaestus.sync` stay the same. The old chart group is rejected, and the server no longer reads the old environment names.

#### 🔴 Update team API callers

- Change `/api/workspaces/{workspaceSlug}/team` to `/api/workspaces/{workspaceSlug}/teams`.
- Use PATCH for team and repository visibility, with a required JSON object `{ "hidden": true }` or `{ "hidden": false }`. Remove the visibility query parameter and primitive boolean body.
- Replace POST `.../teams/{teamId}/labels/{repositoryId}/{label}` with PUT `.../teams/{teamId}/labels/{labelId}`. DELETE uses the same label-id path.

Regenerate callers from the OpenAPI specification. The web app uses the new contract in this release. Repeated label additions and removals keep the same result.

#### 🔴 Stop every role before moving conversation turn leases to agent runs

The leases of conversation turns move from `mentor_turn` to the `agent_run` table.
The previous release cannot read or renew a turn lease in the new table.

1. Stop the server, worker, webhook and conversation loop roles.
2. Back up the database.
3. Upgrade. The migration gives each turn its run.
   A turn that a loop still held when it stopped is a lost attempt: after the upgrade, the server records its usage and queues it again, or fails it after its retry.
4. If you set `hephaestus.mentor.turn.capacity` yourself, set `hephaestus.agent.capacity.turns` instead. The chart and `HEPHAESTUS_MENTOR_TURN_CAPACITY` need no change.
   `hephaestus.mentor.turn.lease` is now `hephaestus.agent.capacity.turn-lease-ttl`. `hephaestus.mentor.turn.lease-reaper-delay` is gone: the run reaper runs every `hephaestus.agent.capacity.lease-renewal`.
5. Start the roles.

#### 🔴 Stop every role before enforcing unique repository monitor names

The database now permits each repository name only once per workspace.
The upgrade stops if duplicate names exist in a workspace.
It does not delete or merge monitors, review settings, or sync progress.

Before you stop roles or start the upgrade, check for duplicates:

```sql
SELECT workspace_id, name_with_owner, count(*) AS monitors
FROM repository_to_monitor
GROUP BY workspace_id, name_with_owner
HAVING count(*) > 1;
```

If this query returns rows, reconcile the monitors against the provider’s repository IDs before you retry.
The database cannot choose which monitor to keep.
Preserve the review settings and sync progress that you need.

Stop the server, worker, and webhook roles before you upgrade.
Back up the database.
Start the roles only after the migration completes.
A failed migration can leave earlier changes of the release applied.
If the upgrade stops, restore the verified backup before you start the previous release.

Migration rollback removes the new constraint and preserves every monitor.

#### 🔴 Stop every role before moving model usage to one row for each call

The usage ledger `llm_usage_event` gets one row for each model call.
The measurements of each review attempt and conversation attempt move into it, and their tables are dropped.
The previous release cannot write a model call's usage to the new ledger.

1. After the upgrade, the proxy refuses calls to a chat model without an output limit.
   When a server starts, it reads missing limits from each model's provider.
   After the upgrade, check that no model in **AI providers** is marked **Limits missing**.
   For such a model, set **Max output tokens** or **Context window**.
   The proxy clamps the output limit of each request to the model's limit.
   An OpenAI-compatible server that ignores the output-limit field can write past the upper bound of a call.
   See [Model limits](https://docs.hephaestus.build/admin/ai-providers#model-limits).
2. Stop the server, worker, and webhook roles.
3. Back up the database.
4. Upgrade. The migration does these steps:
   - Existing ledger rows stay as they are. They keep their calls count and get no run.
   - A measurement that the previous release had not written to the ledger yet, because its attempt still ran, becomes ledger rows: one for the review's own model, one for each practice and model kind of its practice scripts, and one for each conversation attempt. These rows keep the frozen model, price mode and rates. Verified calls on a free model remain free. Other moved measurements stay unpriced until the repricing pass prices them from their frozen rates, or resolves a price that was unknown.
   - Existing rows get no upper bound.
5. Start the roles.

The repricing pass runs every 15 minutes. It now prices unpriced usage of every month, oldest first, up to 5000 rows each pass.
After the upgrade, a capped budget can stay paused for a short time, until the pass prices the moved rows.
The pass can also price rows of earlier months that stayed unpriced because a price was missing, if the price now exists.
This changes the totals of those months.

If the upgrade stops, restore the verified backup before you start the previous release.

#### 🔴 Move the GitHub webhook byte bound into the stream map

The broker is removed; follow [Remove the retired sync broker values before upgrading](https://docs.hephaestus.build/admin/upgrade#remove-the-retired-sync-broker-values-before-upgrading) and its complete removal list instead of the previous broker instructions.

### v1.0.0-rc.5

#### 🔴 Stop every role before requiring native provider IDs

Stop every role before this upgrade.
Create and verify a database backup.
Keep the previous application stopped until the migration completes.
Its bootstrap can still write monitors without provider IDs.
To return to the previous release after any refusal, restore that backup first.
Earlier migrations in the release can already have committed.

The migration preserves monitor IDs, sync checkpoints, retained work, and stop receipts.
It fills missing IDs only from provider identities on the connection's origin that the workspace owns.
A repository needs that workspace's repository item.
A GitLab group needs that workspace's organization connection.
The migration converts path-only GitLab stops to native IDs.
It creates no receipts for old stops.

If a selection is unresolved, invalid, or would duplicate a monitor, the migration refuses before this change writes data.
The refusal names every affected row and links this entry.
The migration never deletes a selection.

Run this read-only query before the upgrade.
It reports only the selections that require operator action.
`row_id` names the monitor for `monitor` and the connection for every other kind.
`workspace_retained_items` counts the whole workspace, including removed items.
It is context, not the selection's retained-work count.
The removal commands check the verified project ID and path instead.

`unresolved_repository_items` counts repository items whose native source no longer exists.
These items prevent removal of a real project because the query cannot prove their project IDs.

```sql
WITH monitor_ids AS (
    SELECT m.id, m.workspace_id, m.name_with_owner AS selection,
        COALESCE(m.native_id, (
            SELECT min(r.native_id) FROM repository r
            JOIN identity_provider p ON p.id = r.provider_id
            JOIN connection c ON c.workspace_id = m.workspace_id AND c.kind = p.type
                AND c.config->>'serverUrl' = p.server_url AND c.state IN ('ACTIVE','SUSPENDED')
            WHERE lower(r.name_with_owner) = lower(m.name_with_owner) AND r.native_id > 0
                AND EXISTS (SELECT 1 FROM item i WHERE i.workspace_id = m.workspace_id
                    AND i.kind = 'scm.repository' AND i.parent_id IS NULL AND i.source_id = r.id)
            HAVING count(DISTINCT r.native_id) = 1
        )) AS native_id
    FROM repository_to_monitor m
), group_ids AS (
    SELECT c.id, c.workspace_id, w.account_login AS selection,
        c.config->'gitlabGroupId' AS stored_id,
        (SELECT o.native_id FROM organization o
         JOIN identity_provider p ON p.id = o.provider_id
         WHERE o.id = w.organization_id AND o.native_id > 0
             AND p.type = c.kind AND p.server_url = c.config->>'serverUrl') AS resolved_id
    FROM connection c JOIN workspace w ON w.id = c.workspace_id WHERE c.kind = 'GITLAB'
), problems AS (
    SELECT 'monitor' AS kind, m.id AS row_id, m.workspace_id, m.selection,
        'No positive provider ID with workspace-owned evidence' AS reason
    FROM monitor_ids m WHERE m.native_id IS NULL OR m.native_id <= 0
    UNION ALL
    SELECT 'monitor', m.id, m.workspace_id, m.selection, 'Duplicate provider ID in this workspace'
    FROM monitor_ids m WHERE m.native_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM monitor_ids other WHERE other.workspace_id = m.workspace_id
            AND other.native_id = m.native_id AND other.id <> m.id)
    UNION ALL
    SELECT 'group', g.id, g.workspace_id, g.selection, 'No positive provider group ID with workspace-owned evidence'
    FROM group_ids g WHERE CASE
        WHEN g.stored_id IS NULL OR g.stored_id = 'null'::jsonb THEN g.resolved_id IS NULL
        WHEN jsonb_typeof(g.stored_id) = 'number' AND g.stored_id::text ~ '^[1-9][0-9]*$'
            THEN g.stored_id::text::numeric > 9223372036854775807
        ELSE true END
    UNION ALL
    SELECT 'stop', c.id, c.workspace_id, path.value, 'No positive provider ID with workspace-owned evidence'
    FROM connection c
    CROSS JOIN LATERAL jsonb_array_elements_text(
        COALESCE(c.config#>'{projectExclusions,unresolvedPaths}', '[]')) path(value)
    WHERE c.kind = 'GITLAB' AND (
        SELECT count(DISTINCT r.native_id) FROM repository r
        JOIN identity_provider p ON p.id = r.provider_id
        WHERE p.type = c.kind AND p.server_url = c.config->>'serverUrl'
            AND lower(r.name_with_owner) = lower(path.value) AND r.native_id > 0
            AND EXISTS (SELECT 1 FROM item i WHERE i.workspace_id = c.workspace_id
                AND i.kind = 'scm.repository' AND i.parent_id IS NULL AND i.source_id = r.id)
    ) <> 1
    UNION ALL
    SELECT 'stop-id', c.id, c.workspace_id, value::text, 'Project stop ID must be a positive JSON integer'
    FROM connection c
    CROSS JOIN LATERAL jsonb_array_elements(
        COALESCE(c.config#>'{projectExclusions,nativeIds}', '[]')) ids(value)
    WHERE c.kind = 'GITLAB' AND CASE
        WHEN jsonb_typeof(value) = 'number' AND value::text ~ '^[1-9][0-9]*$'
            THEN value::text::numeric > 9223372036854775807
        ELSE true END
)
SELECT COALESCE(jsonb_agg(jsonb_build_object(
    'kind', kind, 'row_id', row_id, 'workspace_id', workspace_id,
    'selection', selection, 'reason', reason,
    'workspace_retained_items', (SELECT count(*) FROM item i WHERE i.workspace_id = problems.workspace_id),
    'unresolved_repository_items', (SELECT count(*) FROM item i
        WHERE i.workspace_id = problems.workspace_id AND i.kind = 'scm.repository' AND i.parent_id IS NULL
            AND NOT EXISTS (SELECT 1 FROM repository r WHERE r.id = i.source_id)))
    ORDER BY kind, row_id, selection), '[]') AS issues
FROM problems;
```

For a selection you can repair, use a credential with access to its configured origin.
For GitHub, read `GET /repos/{owner}/{repo}` and record `id`.
For GitLab projects, read `GET /api/v4/projects/{encoded_path}` and record `id`.
For GitLab groups, read `GET /api/v4/groups/{encoded_path}` and record `id`.
Verify the returned identity against the intended selection.

If a path was reused, use the original provider ID from your records.
Do not assign the replacement project's ID to an earlier selection.
Set the verified monitor ID in `native_id`.
Set a verified group ID in `config.gitlabGroupId` as a positive JSON number.
For a path-only stop, add its verified ID to `config.projectExclusions.nativeIds`.
Remove only that path from `config.projectExclusions.unresolvedPaths`.

Keep `stopReceipts` and all other configuration fields unchanged.
The migration removes the empty `unresolvedPaths` field.
For a stop that has no receipt, stop the project again before you remove its retained work.
For duplicate monitors, reconcile their review settings under the previous release before this upgrade.
Preserve both rows until that reconciliation is complete.

**Remove an abandoned selection only after both checks pass.**
First, obtain provider proof that the selected project or group was deleted, or its installation is gone.
A provider can also confirm that the selected path never identified a real project.
A missing credential, a 403, or a 404 alone does not prove deletion.

Second, use reliable provider records to identify the original project ID.
For a real project, use that provider ID as `native_id` in the commands below.
Do not use a database row ID or another project's ID.

Use SQL `NULL` only when provider proof establishes that the selection never identified a real project.
An empty local mirror does not establish that proof.

Keep every role stopped while you act on that result.
The commands refuse if the workspace retains a repository item for that provider ID, even after a rename.
They also refuse if the workspace retains a repository item at the selected path.
Removed items still count.
Other projects and conversation items do not block removal.

An unknown source origin prevents removal.
For a real project, an unresolved repository item also prevents removal.
If a real project's original ID is unknown, preserve the selection until a provider administrator can identify it.

If both checks pass, remove only the reported selection.
With psql, set the variables below to the values in the row report.
For a monitor, use its `row_id` as `monitor_id`:

```sql
DELETE FROM repository_to_monitor m
WHERE m.id = :monitor_id AND m.workspace_id = :workspace_id
    AND (:native_id IS NULL OR :native_id > 0) AND (m.native_id IS NULL OR m.native_id = :native_id)
    AND EXISTS (SELECT 1 FROM connection c JOIN identity_provider p
        ON p.type = c.kind AND p.server_url = c.config->>'serverUrl'
        WHERE c.workspace_id = m.workspace_id AND c.kind IN ('GITHUB','GITLAB'))
    AND NOT EXISTS (
        SELECT 1 FROM item i LEFT JOIN repository r ON r.id = i.source_id
        LEFT JOIN identity_provider p ON p.id = r.provider_id
        WHERE i.workspace_id = m.workspace_id AND i.kind = 'scm.repository' AND i.parent_id IS NULL
            AND ((r.id IS NULL AND :native_id IS NOT NULL) OR (
                (r.native_id = :native_id OR lower(r.name_with_owner) = lower(m.name_with_owner))
                AND EXISTS (SELECT 1 FROM connection c WHERE c.workspace_id = m.workspace_id
                    AND c.kind = p.type AND c.config->>'serverUrl' = p.server_url)
            ))
    );
```

For a path-only stop, use its `row_id` as `connection_id` and its `selection` as `stopped_path`:

```sql
UPDATE connection c SET config = jsonb_set(c.config, '{projectExclusions,unresolvedPaths}', (
    SELECT COALESCE(jsonb_agg(value), '[]')
    FROM jsonb_array_elements(c.config#>'{projectExclusions,unresolvedPaths}') stops(value)
    WHERE value <> to_jsonb(:'stopped_path'::text)
))
WHERE c.id = :connection_id AND c.workspace_id = :workspace_id AND c.kind = 'GITLAB'
    AND (:native_id IS NULL OR :native_id > 0)
    AND EXISTS (SELECT 1 FROM identity_provider p
        WHERE p.type = c.kind AND p.server_url = c.config->>'serverUrl')
    AND NOT EXISTS (
        SELECT 1 FROM item i LEFT JOIN repository r ON r.id = i.source_id
        LEFT JOIN identity_provider p ON p.id = r.provider_id
        WHERE i.workspace_id = c.workspace_id AND i.kind = 'scm.repository' AND i.parent_id IS NULL
            AND ((r.id IS NULL AND :native_id IS NOT NULL) OR (
                p.type = c.kind AND p.server_url = c.config->>'serverUrl'
                AND (r.native_id = :native_id OR lower(r.name_with_owner) = lower(:'stopped_path'))
            ))
    );
```

For an abandoned group connection, erase its empty workspace through instance administration before the upgrade.
After a migration attempt, restore the backup first.
Start the previous release to erase that empty workspace.
Stop every role before you retry the upgrade.
Do not fabricate a group ID.

Check that the command changes exactly one row.
If it changes no row, preserve the selection and check its reported identity and retained items.
Repair its ID, or use the existing workspace erasure procedure after the required approval and backup.
Do not remove retained work solely to pass the migration.

Run the report again after each repair or removal.
Retry the migration only when the report returns `[]`.
Restart the upgraded roles only after it completes.

#### 🔴 Pull-based hosts with tooling from before 1.0.0-rc.4 need one manual step

The reconciler verifies a release with the tooling of the release before it. Tooling from before 1.0.0-rc.4 expects five chart images, and 1.0.0-rc.4 has nine, so it stops each run with `does not pin the v1.0.0-rc.4 images`. The running release is not affected. Helm installs that do not use pull-based deployment need no action.

Follow [Hosts with reconciler tooling from before 1.0.0-rc.4](https://docs.hephaestus.build/admin/upgrade#hosts-with-reconciler-tooling-from-before-100-rc4) once, after the host runs 1.0.0-rc.4 or later, and before you resume the timer.

#### 🔴 Stop every role before product feedback renames its stored states

Stop every role before this upgrade.
Create and verify a database backup.
The migration renames the state `EMAILED` to `SAVED` and the route `EMAIL` to `ADMINS`, so the previous application cannot read those rows.
The rows and their reports stay as they are.
To return to the previous release, follow [Rollback](https://docs.hephaestus.build/admin/upgrade#rollback) and restore that backup first.

#### 🔴 Name the page route and the release in your privacy notice

Each product feedback report now carries the page route and the release without a step.
A route is a template with no name or ID, such as `/w/$workspaceSlug/activity`.
It reaches a public issue only with the reporter's choice.

If your privacy notice lists what a report carries, add these two items before you upgrade.
[Legal pages](https://docs.hephaestus.build/admin/legal-pages#product-feedback-on-github) has the text.

#### 🔴 Git cache sizes are whole `Mi` or `Gi`

`server.gitCache.sizeLimit` and `worker.gitCache.sizeLimit` now also set the size at which a role deletes its least recently used Git mirrors, so the chart reads them as a number.

The chart refuses any other form, such as `20G` or `1.5Gi`.

**Action:** before you upgrade, write each value as a whole number of `Mi` or `Gi`, such as `20Gi`. Values from the shipped profiles need no change.

### v1.0.0-rc.4

#### 🔴 The alert rule file has a new path

**Affected**: deployments that load the alert rules from the repository or from a release's source tree.

The rule file and its promtool tests moved from `deploy/observability/` to `deploy/chart/hephaestus/files/`, so that the chart can render them. A rule loader that still reads the old path finds no file.

**Migration**: load `deploy/chart/hephaestus/files/hephaestus-alerts.yml` from the release that you run. Or let the chart give your cluster the rules with each upgrade:

- Set `observability.alertRules.configMap`, mount the ConfigMap into your Prometheus or vmalert, and point its rule loader at the mounted file.
- Set `observability.alertRules.prometheusRule`, and make the `ruleSelector` and `ruleNamespaceSelector` of your Prometheus match it. `observability.alertRules.labels` adds the labels that the selector reads.

After the upgrade, check that your Prometheus or vmalert lists the Hephaestus rules.

#### 🔴 Three model call metrics have new names

**Affected**: deployments with their own alerts or dashboards on the LLM proxy metrics. The shipped alert rules read none of them.

| Old metric | New metric |
| --- | --- |
| `llm_proxy_duration_seconds` | `gen_ai_client_operation_duration_seconds`, by `pool` and `class`, from the pool's grant to the end of the call |
| `sandbox_gateway_calls_in_flight_refused_total` | `llm_proxy_refused_total{refusal="calls_in_flight"}` |
| `llm_proxy_budget_blocked_total` | `llm_budget_blocked_total{surface="model_call"}`, with the same `cap` label |

A query on an old name does not fail. It returns no series, so an alert built on it stops firing without a sign.

**Migration**: change those queries when you upgrade. The old duration included the wait for a pool permit, which `llm_pool_permit_wait_seconds` measures on its own.

### v1.0.0-rc.3

#### 🔴 Give the bundled NATS one memory size, in Mi or Gi

The chart now sets the bundled broker's `GOMEMLIMIT` to 80 % of `nats.resources.limits.memory`, so it reads that limit. A render stops with `nats.resources.limits.memory must be a whole number of Mi or Gi` when the limit uses another unit or a fraction, such as `3G`, `1.5Gi` or a number of bytes.

The chart also requires the memory request to equal the limit. The broker's heap can grow to its soft limit during a catch-up, and with a request below that, the broker is among the first Pods that the node evicts when it runs short of memory.

**Action:** if your values set `nats.resources.requests.memory` or `nats.resources.limits.memory`, set both to the same size in `Mi` or `Gi`, or remove both to take the new 1 GiB. A values file that raised only the request, for example `requests: { memory: 3Gi }`, now stops the render with `nats.resources.requests.memory (3072Mi) must equal nats.resources.limits.memory (1024Mi)`.

#### 🔴 Replace `worker.reviews` with the memory that review Pods get

The chart no longer reads a review count per worker. It derives the reviews that run at once from `worker.sandbox.reviewMemory`, the memory that your sandbox nodes give review Pods, and the request of one review Pod. Each worker runs as many of them as its memory limit holds, and the reviews quota holds them once. The values schema refuses `worker.reviews`, so a values file that still sets it stops the render.

**Action:** remove `worker.reviews` from your values file. To keep the same number of reviews, set `worker.sandbox.reviewMemory` to your old `worker.reviews` times `worker.replicas` times 384Mi. For example, 2 workers of 5 reviews become `reviewMemory: 3840Mi`, which is the default. With a RuntimeClass overhead in `worker.runtime.podOverhead`, add that overhead to the 384Mi.

The check on `worker.resources.limits.memory` now also counts the worker's 128 MiB of direct memory, which the JVM takes from the heap. One review needs at least 1023 MiB, and each further review 18 MiB. A limit that the old check passed can now hold fewer reviews per worker, and a limit below 1023 MiB stops the render with `worker.resources.limits.memory must be at least 1023Mi`. To keep the same reviews per worker, raise the limit by 128 MiB.

### v1.0.0-rc.2

#### 🔴 Check practice scripts that decide after a failed read

A practice script that returns its own result now needs the evidence that it asked for. It stops with the reason **Decided without its evidence** if a read failed, even if it caught the error, or if it read a record that was not captured.

This changes nothing for a script that returns `ctx.review()`. Every script in the shipped catalog does.

If you wrote a practice script that catches a read failure and then returns its own result:

1. Open the practice in the practice editor.
2. Let the review decide with `ctx.review`, or let the failure end the script.

#### 🔴 Choose how long conversations with Hephaestus stay

Conversations with Hephaestus now have a retention limit of 400 days. Each message, answer, digest and summary is erased in the two weeks before it is 400 days old, as a deleted message is. What Hephaestus read during an answer is erased with the workspace's Slack retention, 30 days by default, because it can copy messages from monitored Slack channels.

The first weekly runs after the upgrade erase every entry that is older, and the erasure cannot be undone.

To keep conversations longer:

1. Before you upgrade, set the Helm value `config.conversationRetention`, for example `P730D`. The value is at least `P15D`.
2. Upgrade.

#### 🔴 Remove `interactiveReserve` from call pool requests

A call pool's limit is now learned from the provider, and that limit sets the conversation reserve.
`interactiveReserve` no longer exists in `/api/admin/llm/call-pools` requests or responses, and the database drops the column.
Change any script that sends or reads this field.

The upgrade gives every model without a pool a pool of its own, which starts at 4 calls in flight.
A pool's existing `maxConcurrentCalls` stays as a ceiling on the learned limit, and that pool also starts at 4 calls in flight.
Its limit rises while the provider serves all calls.
[Sharing provider capacity](/admin/ai-providers#6-sharing-provider-capacity) tells what you can still set.

#### 🔴 Give the one-host installer a login provider

The one-host installer no longer writes or keeps a `site.yaml` without a login provider.
Such an instance starts, but nobody can sign in to it.

- For a scripted first install, give `--github-client-id` and set `GITHUB_OAUTH_CLIENT_SECRET`.
  On a terminal, the installer asks for both.
- Before a repeat run, make sure that `site.yaml` names a login provider.
  Set `config.github.oauthClientId`, or enable GitLab with `config.gitlab.enabled: true` and `config.gitlab.oauthClientId`.
  [First steps § 1](https://docs.hephaestus.build/admin/first-steps#1-configure-a-login-provider) describes the GitHub OAuth app.

#### 🔴 Download the k3s bundle to install or upgrade a one-host deployment

A release no longer publishes `k3s-install.sh` and its seven sibling files as separate assets.
To install or upgrade, download `hephaestus-k3s-<version>.tar.gz` and its `.sigstore.json` signature.
Verify the bundle with `cosign verify-blob`, unpack it with `tar xzf`, and run `sh k3s-install.sh` from the unpacked directory.
[Install on one host](/admin/kubernetes#install-on-one-host-k3s) gives the commands.
A script that downloads the single `k3s-*` assets fails on a new release until you change it to download the bundle.

#### 🔴 Install 1.0.0-rc.1 before you upgrade a database from 0.x

This release starts from a baseline with the schema of 1.0.0-rc.1, and no longer holds the 0.x migrations.

A database from before 1.0.0-rc.1 is refused before anything changes. Install 1.0.0-rc.1 with its [versioned upgrade instructions](https://github.com/hephaestus-build/Hephaestus/blob/v1.0.0-rc.1/docs/admin/upgrade-to-1-0.mdx), complete its upgrade, then upgrade to this release.

A database on 1.0.0-rc.1 needs no action. The migration compares its schema with 1.0.0-rc.1, records the baseline without changing the schema, and continues. If the schema differs, or if a later build ran on the database, the migration stops without a change and lists what it found. The current release can keep running: contact the maintainers with the list.

An installation that once ran the former intelligence service can still hold its tables: `checkpoints`, `checkpoint_blobs`, `checkpoint_writes`, `checkpoint_migrations`, `store` and `store_migrations`, and the function `text_to_lo(text)`. Hephaestus does not use them, and the migration stops and lists them. Back them up, drop them, and run the migration again. Connect as the owner of the Hephaestus database (here `hephaestus`):

```bash
pg_dump --format=custom --file=intelligence-leftovers.dump \
  --table=public.checkpoints --table=public.checkpoint_blobs --table=public.checkpoint_writes \
  --table=public.checkpoint_migrations --table=public.store --table=public.store_migrations hephaestus
psql --dbname=hephaestus --command='DROP TABLE IF EXISTS public.checkpoints, public.checkpoint_blobs, public.checkpoint_writes, public.checkpoint_migrations, public.store, public.store_migrations; DROP FUNCTION IF EXISTS public.text_to_lo(text);'
```

The tables hold old conversation memory, so keep the backup only as long as your retention rules allow.

#### 🔴 Update API clients that read paged lists

This release has two page shapes. A client that still reads an old field gets no value for it.

A numbered list returns `{content, page, size, totalElements, totalPages}`:

- These fields are gone: `number`, `numberOfElements`, `first`, `last`, `empty`, `sort` and `pageable`. Read `page` for `number`. There is another page when `page + 1 < totalPages`.
- `GET /admin/product-feedback` moves `page.number`, `page.size`, `page.totalElements` and `page.totalPages` to the top level.
- `GET /admin/users` returns this object, not a bare array.

A long append-only list returns `{content, nextCursor}`. Pass `nextCursor` as `cursor` to read the next page. The last page has no `nextCursor`.

| Endpoint | Change |
| --- | --- |
| `GET /admin/audit` and `GET /admin/config-audit` | Replace `page` with `cursor`. The response is a cursor page, so `size`, `totalElements`, `totalPages` and the numbered-page fields are gone. For the total, read `/count` under the same path with the same filters. |
| `GET /workspaces/{workspaceSlug}/config-audit` | The same change, and the same `/count` read. |
| `GET /admin/integration/dead-letters` and `GET /workspaces/{workspaceSlug}/integrations/dead-letters` | The bare array becomes a cursor page. Replace `after` with `cursor`. |
| `GET /workspaces/{workspaceSlug}/practices/scans/{scanId}/report/items` and `/problems` | `hasNext` is gone. There is another page when `nextCursor` is present. |
| `GET /workspaces/{workspaceSlug}/mentor/entries` | `entries` becomes `content`, and `earlierBefore` becomes `nextCursor`. Replace `before` with `cursor`, and `limit` with `size`. |
| `GET /workspaces/{workspaceSlug}/mentor/entries/{entryId}/thread` | The same parameters. `entries` and `earlierBefore` move into `replies`, as `replies.content` and `replies.nextCursor`. |
| `POST /workspaces/gitlab/projects` | `projects` becomes `content`, and `nextPage` becomes `nextCursor`. Replace `page` with `cursor`. |

#### 🔴 Rewrite custom practice scripts for the public script API

Practice scripts now import only `@hephaestus/practice-scripts`, `@hephaestus/practice-scripts/scm`, `/chat` and `/docs`. The upgrade rewrites every practice whose current script is exact bundled text, whether or not you customized its other fields. Your own script text is not changed, and the upgrade does not stop for it.

Until you rewrite such a script, its practice is withheld with the reason **Not a runnable script**, and the review ends as Partial. It makes no claim about the work.

To find these scripts after the upgrade, run:

```sql
SELECT p.workspace_id, p.slug
FROM practice p
JOIN practice_version v ON v.id = p.current_version_id
WHERE v.definition->>'script' LIKE '%../lib/core/%'
   OR v.definition->>'script' LIKE '%../kits/%';
```

For each script:

1. Replace the imports. `definePracticeScript`, `UnratedError` and `listWithin` come from `@hephaestus/practice-scripts`. `scm` and the SCM helpers come from `@hephaestus/practice-scripts/scm`.
2. Replace `const domain = scm(ctx); expectKind(domain, "scm.pull_request");` with `const work = scm(ctx, "scm.pull_request");`.
3. Replace `requireChange(domain.files)` with `work.changedFiles()`, `domain.files?.head` with `work.head`, and `selectedSources` with `work.sources`.
4. Replace `readCapturedCommits(ctx.evidence)` with `work.commits()`. The other record readers are methods of `work` too.
5. Replace `occurrences` and `scanOccurrences` with `work.sites`.
6. Move any other helper into your script.

**What happens to results.** The rewritten scripts are new practice versions, so their earlier results are no longer current. Nothing reviews again because of the upgrade. A practice is judged again at its next occasion for each item. Results and feedback that you already have stay as they are.

**Scheduled reviews.** The next scheduled batch and repository scan measure their whole scope again, once, because reuse needs the same practice version. This costs model usage once. A scan report cannot compare across this change.

**Reviews in flight.** A review that is queued or running during the upgrade still runs the script version that it pinned. That practice is withheld with the reason above, and its next occasion judges it again.

**Removed.** `branches-from-the-integration-branch` had no use and no catalog entry. A practice with its exact text is withheld with the reason above until you replace its script.

#### 🔴 Delete GitLab hooks on the shared endpoint

This release removes `POST /webhooks/gitlab`. A hook that still posts there receives an error, and Hephaestus does not record its events.

1. Before you upgrade, open **Settings → Webhooks** of each GitLab group and project that a workspace connects.
2. Delete each hook whose URL ends in `/webhooks/gitlab`.
3. Keep each hook whose URL contains `/webhooks/gitlab/connections/`. The workspace's GitLab connection registered it.
4. Keep the previous release running until it has processed the events that the deleted hooks already delivered. Its GitLab consumers then have no pending messages.
5. Upgrade. If a connected group has no connection hook, Hephaestus registers one at its next webhook health check, within six hours.

Events that GitLab sends to the shared endpoint, or before the connection hook exists, are lost. So are shared-endpoint events that the previous release did not process before the upgrade: the new release does not read them. This includes push events. The next periodic reconcile recovers the effect of lost events on issues, merge requests and notes.

`WEBHOOK_SECRET` stays required. It now verifies GitHub webhooks only. GitLab hooks use the token that `WEBHOOK_ROUTING_SECRET` signs.

#### 🔴 Enable user namespaces on sandbox nodes

Before upgrading, ensure that every sandbox node supports [Kubernetes user namespaces](https://kubernetes.io/docs/concepts/workloads/pods/user-namespaces/).
The nodes need Linux 6.3 or later and a compatible container runtime, such as containerd 2.0 or later.
If you use gVisor (`runsc`) or another RuntimeClass that is not qualified with user namespaces, clear `worker.runtime.runtimeClassName` to use the default runtime or qualify a compatible runtime before upgrading.
A RuntimeClass does not replace user namespaces.
Every sandbox and probe Pod now sets `hostUsers: false`, including when an optional RuntimeClass is configured.
The worker starts no sandbox until its isolation probe confirms a private user mapping.
Check that `sandbox_isolation_passed` and `sandbox_isolation_user_namespaces` are both `1` after the worker starts.
See [Sandbox prerequisites](https://docs.hephaestus.build/admin/kubernetes#sandbox-prerequisites).

#### 🔴 Remove `PRACTICE_REVIEW_PRACTICE_CONCURRENCY`, and check two review budgets

A practice review now sets its own pace, so Hephaestus no longer reads `PRACTICE_REVIEW_PRACTICE_CONCURRENCY`.

If you set this variable, remove it. If you used a low value to protect your model provider, set a limit on its [call pool](https://docs.hephaestus.build/admin/runtime-roles#call-pools) instead.

If you set `PRACTICE_REVIEW_PRACTICE_MODEL_CALLS` or `PRACTICE_REVIEW_PRACTICE_OUTPUT_TOKENS`, multiply the value by 3 to keep your budget. Each practice now gets exactly the value that you set. The old value applied at least three times over.

#### 🔴 Stop every role: the practice-review assignment's "Max concurrent reviews" is removed

The upgrade drops the column that stored **Max concurrent reviews**. A role that still runs the earlier version cannot read the new schema, so stop every role before the upgrade.

Reviews are now paced by capacity: a worker's review slots, the reviews quota of its sandbox namespace, and the fair grants of the model's call pool. To limit how much one provider serves, set a limit on its [call pool](https://docs.hephaestus.build/admin/runtime-roles#call-pools).

#### 🔴 Check practice scripts that change a result after `ctx.review`

A practice script now ends when it calls `ctx.review`. Code after that call does not run, and the review's result is the practice's result.

This changes nothing for a script that returns `ctx.review()`. Every script in the shipped catalog does.

If you wrote a practice script that reads or changes the result after the call, or catches a failed review:

1. Open the practice in the practice editor.
2. Move that logic before `ctx.review`, or give it to the review as `directions`.

#### 🔴 Apply the 72-hour horizon to Slack and Outline on the small profile

The `small` profile kept Slack and Outline deliveries for seven days. These streams carry personal content, and an erasure request cannot reach inside them, so they now keep deliveries for 72 hours, as on every other profile.

A new installation has this at once. So does a stream that holds nothing older than 72 hours. On any other stream, the upgrade alone does not apply it: startup withholds a shorter age, because that deletes stored messages.

**The one action:** check that `webhook.stream.consumer.pending` and `webhook.stream.consumer.ack.pending` are near zero, set `webhook.stream.allowDestructiveLimitUpdates: true` for one upgrade, then set it back to `false`.
The flag applies to every stream. If your site values also lower a bound on another stream, that bound takes effect too, and it deletes what no longer fits.

**What shows that it is not in effect yet:** the alert `HephaestusWebhookStreamPastAgeBound`, and `webhook.stream.oldest.message.age` above `webhook.stream.age.bound` for `slack` or `outline`.

After this change, a stored event older than 72 hours can no longer be replayed: its replay returns `410`. An outage longer than 72 hours loses older Slack and Outline events from the stream:

- The nightly Slack history sync refills messages in active monitored channels. It also removes a stored message that a complete read of Slack no longer returns. The admin guide's data retention page, under *Messages deleted or edited in Slack*, lists the messages that it does not compare.
- The Outline sync restores each document's current state, and it removes deleted documents on a clean reconciliation pass.

#### 🔴 Stop every role before you upgrade from 1.0.0-rc.1

1.0.0-rc.1 cannot run on the schema of this release.
The migration drops the column `llm_call_pool.interactive_reserve` and replaces the unique constraint `uq_issue_provider_native_id`, and 1.0.0-rc.1 still uses both.

Follow [Stopped schema upgrades](https://docs.hephaestus.build/admin/kubernetes#stopped-schema-upgrades):

1. Back up PostgreSQL and JetStream, and verify the restore.
2. Stop every role with the chart of 1.0.0-rc.1: the server, the workers and the webhook.
   Wait until their Pods and the sandbox Pods and Jobs are gone.
3. Run the migration of this release with every role stopped.
4. Start every role from this release.

Do not use `--rollback-on-failure`.
With [pull-based deployment](https://docs.hephaestus.build/admin/pull-based-deployment#operating-it), pause the reconcile timer before step 2.
For an upgrade within one major version, the reconciler does not stop the roles.
Promote this release and resume the timer only after step 4.

To roll back, stop every role and restore the backup from step 1. Then start 1.0.0-rc.1.
There is no in-place downgrade.
If the migration fails, follow [If migration fails](https://docs.hephaestus.build/admin/upgrade#if-migration-fails).

### v1.0.0-rc.1

#### 🔴 Stop every role before the conversation lease cutover

Stop all roles before the 1.0 upgrade. Apply the database migration before you start the new release.
Conversation turns now use a lease ID for each attempt. The migration removes the worker ID from these rows.
Old attempt credentials cannot resume an answer. Restore the verified pre-upgrade database backup before you start the previous release.

#### 🔴 Account for the single-node worker deployment outage

The `small` profile now uses `Recreate` for the worker. The `small-16gib` profile keeps `Recreate`. Each upgrade drains and stops the old worker before starting its replacement. Reviews and conversation turns pause in that interval. Measure the outage against the 99.5% availability budget before promotion.

Both profiles admit 32 resident turns from the declared CPU budget. The conversations quota keeps its existing ten tools-Pod slots, including up to two warm Pods. A turn uses tools only on demand and waits when that quota is full. No Pod request or limit is reduced. An explicit `worker.conversationTurns` still overrides the automatic count.

#### 🔴 Move an existing installation to Helm before upgrading

Follow the [0.95 migration guide](https://github.com/hephaestus-build/Hephaestus/blob/v0.95.0/docs/admin/compose-to-kubernetes.mdx), then install 0.95.1 before upgrading to 1.0. Keep the matching database backup, broker recovery material, encryption keys and signed release lock together. The [Kubernetes guide](https://docs.hephaestus.build/admin/kubernetes) owns installation, backup and recovery. Compose remains only for local development infrastructure.

#### 🔴 Move release reconciliation to Helm

Before upgrading to 1.0, move Compose installations to the Helm chart on k3s or Kubernetes.
Follow the [versioned migration guide](https://github.com/hephaestus-build/Hephaestus/blob/v0.95.0/docs/admin/compose-to-kubernetes.mdx).
Keep the application version at 0.95.0 during that move.

Install the 0.95.1 reconciler before promoting 1.0, as [pull-based deployment](https://docs.hephaestus.build/admin/pull-based-deployment#follow-a-channel-with-the-chart) describes.
Pause reconciliation and wait for its active run to finish before replacing the units and tooling link.
Put site values in `/etc/hephaestus/chart.yaml` and credentials in cluster Secrets.
Keep the existing database credentials, encryption keys, auth keys and provider credentials.
Remove `HEPHAESTUS_STACKS` and `HEPHAESTUS_MIN_FREE_GIB` from `/etc/hephaestus/reconcile.env`.
Set `HEPHAESTUS_WAIT_TIMEOUT=1200`, or keep a larger timeout that your deployment needs.
The reconciler now uses Helm for every deployment.

For the major upgrade, follow [Database maintenance](https://docs.hephaestus.build/admin/kubernetes#database-maintenance) with all writers stopped.
If the cutover stops, keep reconciliation paused and follow [Recover a stopped cutover](https://docs.hephaestus.build/admin/pull-based-deployment#recover-a-stopped-cutover).
A rollback across the 1.0 schema boundary requires a database restore, even with `allowRollback`.

#### 🔴 Use the chart release lock

Before upgrading, move Compose deployments to the supported Helm installation on k3s or Kubernetes.
Update custom release consumers to download `release-vX.Y.Z.chart.json` and `release-vX.Y.Z.chart.json.sigstore.json`.
The schema-2 lock covers the chart digest, image digests, and source commit.
The release no longer publishes the schema-1 `release-vX.Y.Z.json` asset or its signature bundle.

Replace the positional environment-output command with `release-image-lock.ts verify <lock.json> <manifest.json> <release>`.
This command verifies the joint lock against the evidence manifest and writes no environment file.
The `projection` command is removed.

Current release tools refuse versions before 0.75.0 because the supported identity map no longer contains their namespace and signing repository.
Use the tooling from those original releases when you must inspect their archived artifacts.

#### 🔴 Complete the 0.95.0 database upgrade first

If your database is older than 0.95.0, upgrade it to 0.95.0 with that release's signed image lock and [versioned upgrade guide](https://github.com/hephaestus-build/Hephaestus/blob/v0.95.0/docs/admin/upgrade.mdx). Run that release to completion before you start the target release.

The target release checks the final 0.95.0 migration identity before update or synchronization. It refuses an older history. Do not synchronize to bypass this check. Fresh databases need no intermediate release.

#### 🔴 Move custom pgBackRest configuration

If you edited `docker/self-host/pgbackrest.conf`, move those edits to `deploy/postgresql/pgbackrest.conf` before upgrading.
The backup and restore overlays now mount that file.
The PITR drill uses the same configuration.

#### 🔴 Plan node drains before removing disruption budgets

The chart now blocks voluntary eviction of bundled PostgreSQL and NATS, the server, and a singleton worker. A node drain waits until you remove those budgets for planned maintenance.

Pause reconciliation, record the replica counts, and back up the database and broker. Cordon the node, scale workers to zero, and wait for their Pods and sandboxes to stop while the database and broker remain available. Then stop the server and receiver, remove only this release's PodDisruptionBudgets, and drain the node. After maintenance, uncordon the node and reapply the verified release with its saved values to restore the replicas and budgets. Verify them before you resume reconciliation.

Follow [Planned node maintenance](https://docs.hephaestus.build/admin/kubernetes#planned-node-maintenance) for the commands. The default worker drain is now thirty minutes, with termination grace of drain plus 120 seconds. The 16 GiB profile keeps its ten-minute drain and Recreate updates. A host shutdown still has only five minutes, so it relies on durable retry for interrupted reviews.

#### 🔴 Remove the obsolete webhook concurrency setting

Remove `hephaestus.integration.consumer.max-ack-pending` from your configuration. The consumer now enforces one outstanding event per workspace and stream. Temporary failures retry without a delivery limit. Set retry base and maximum delays to at least one second. Unknown failures use `hephaestus.integration.consumer.poison.max-redeliver` (default 10). Apply the database migration before starting the server. Dead-letter records contain metadata only; replay reads the original event from NATS before its retention expires. A database rollback is blocked if installation replay audit records exist. Otherwise, it drops the dead-letter table and destroys its pending replay records. Back up the table before a rollback.

#### 🔴 Stop every role before the Kubernetes-only sandbox cutover

Stop the server, worker, and webhook roles. Take a verified database backup. Apply the migration, then start all roles from the same release through the Helm chart. The old runtime must not run on the new schema.

Remove `HEPHAESTUS_SANDBOX_RUNTIME`, all `SANDBOX_DOCKER_*` keys, `DOCKER_GROUP_ID`, `SANDBOX_MEMORY_BYTES`, and `SANDBOX_PIDS_LIMIT`. The Docker sandbox backend and socket access are removed. The chart's Pod-kind resource table sets sandbox memory and storage; the node enforces the process limit.

The migration promotes version 8 review configurations and removes their unused `allowInternet: false` field. It preserves every other frozen field and rejects a version 8 row with a different internet-access value. Older snapshot formats remain unchanged. For rollback, stop every role and restore the database backup taken before the upgrade.

Local development runs the server and worker in the host JVM through `vp run dev`. Each worktree has its own restricted sandbox namespace and quota in the shared k3d cluster. Use `vp run dev:kubernetes` for the one on-demand chart qualification release. See the contributor local-development guide.

#### 🔴 Stop every role before the rc.1 admission cutover

Include this change in the stopped rc.1 upgrade. Stop every runtime role before you run the migration. The migration removes claim-only waiters and closes unused provider grants. It preserves actual calls, their rate debits, and scheduling history. Then start every role from the new release. Older workers must stay stopped because they can recreate the retired reservations.

Keep the verified pre-upgrade backup until the cutover is complete. To return to the older release, stop every role and restore that backup before you start it. Do not roll the data migration back in place.

#### 🔴 Update API clients that read error codes

This release reports every API error in one RFC 9457 problem model.
A client tells problems apart by the `type` member.
Stored data does not change.

Each `type` is an absolute address of the form `https://docs.hephaestus.build/contributor/api-error-handling#<slug>`.
[API error handling](https://docs.hephaestus.build/contributor/api-error-handling#problem-types) lists every slug.
A problem without its own kind keeps `about:blank`, so its HTTP status is its kind.

Before you upgrade, find each place where your API clients or scripts read an error body.
Replace each removed member in the first column with the member in the second column.

| Removed | Read instead |
| --- | --- |
| `code`, `reason`, `kind` or `reasonCode` that names the kind of error | `type` |
| `code` on a refused feedback approval | `type` `…#feedback-withheld` and `withholdingReason` |
| `error` on a failed OAuth callback | `type` `…#oauth-callback-failed` and `callbackError` |
| The relative `/problems/<slug>` types and `urn:hephaestus:csrf` | The absolute `type` with the same slug, and `…#csrf-token-rejected` |
| The `{ "error": "…" }` body | The problem body |
| `ownerUserId` in the request that creates a workspace | Nothing. The signed-in creator owns the workspace. |

The `/internal/**` sandbox endpoints are no longer in the published API description.
Only the sandboxes call them.

#### 🔴 Convert stored secrets before you start the new server

Back up the database and encryption keys. Stop all database writers, then run the database migration with the existing `HEPHAESTUS_SECURITY_ENCRYPTION_KEY` and `HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY`. Keep the active credential key version. Configure a key only if it is missing. Keep the prior credential key and its version configured if stored credentials still use them.

For chart hosts, use [Stopped schema upgrades](https://docs.hephaestus.build/admin/kubernetes#stopped-schema-upgrades).

The migration encrypts plaintext login-provider and LLM connection secrets. It seals active and inactive JWT private keys without changing their signing identity. It authenticates integration credentials with no key version against the configured active, prior and legacy system keys, then encrypts them with the active credential key and records its version.

If a key is missing or cannot decrypt a stored credential, the conversion stops and rolls back. Keep writers stopped, correct the key configuration, and retry. Start the server only after the migration succeeds. The new server cannot read or write plaintext secrets.

#### 🔴 Recalculate custom PostgreSQL connection budgets

The small profile now has an explicit PostgreSQL connection ceiling and separate role pools.
Apply the change through the existing [stopped database-maintenance procedure](https://docs.hephaestus.build/admin/kubernetes#database-maintenance).
Do not lower the database ceiling while older, larger pools are still running.
For custom worker replica counts or pool sizes, set `postgresql.parameters.max_connections` and each role's `databasePool` values together before upgrading.
The chart rejects a ceiling that cannot hold the configured worker overlap and maintenance reserves.
See [Database connections](https://docs.hephaestus.build/admin/sizing#database-connections) for the calculation.

Check node capacity against the full `postgresql.resources` and `postgresql.backup.resources` memory requests before upgrading.
Count the resident backup sidecar and each backup or stanza Job separately.
Existing memory limits remain in place until representative load qualifies lower ceilings.

#### 🔴 Remove the auth rate-limit store setting

Rate limits on sign-in and other sensitive endpoints now always use PostgreSQL, so all server replicas share them.
The limits and their defaults do not change.

| Removed | Action |
| --- | --- |
| `HEPHAESTUS_AUTH_RATE_LIMIT_POSTGRES_BACKED` (`hephaestus.auth.rate-limit.postgres-backed`) | Remove it from your configuration. It no longer has any effect. |

#### 🔴 Stop every role for the upgrade to 1.0

Upgrade to 0.95.x on the Helm chart first. Follow [Upgrade from 0.x to 1.0](https://docs.hephaestus.build/admin/upgrade-to-1-0). Back up PostgreSQL and JetStream, verify the restore, and export each survey's results before migration.

Apply ADR 0070’s cutover order: stop the server and workers and wait until their sandbox Pods and Jobs are gone. Keep the database-free 0.95.x webhook receiver accepting into JetStream during this drain. The 1.0 chart then replaces that receiver and runs the migration with workers stopped. Start the new server alone, then restore the recorded replica counts from the selected chart. The webhook receiver exception applies only to this cutover’s drain; no old database writer runs during or after migration. Do not run old application writers on the new schema or use an automatic Helm rollback after migration.

Claimed reviews resume without spending a retry and keep their admitted and staged results. A conversation turn in progress ends with “Ask again” and is not retried silently. Recovery after a failed migration requires the verified paired backup and the previous release. Preserve encryption keys and their versions, and apply the individual setting and data steps in this release guide before starting the new roles.

#### 🔴 Update API clients that call the removed operations

This release removes the 21 API operations that 0.95.0 deprecated.
A request to a removed operation now fails.
Stored data does not change.

Before you upgrade, find each removed operation in your API clients and scripts.
Replace it with the operation in the second column.
Paths are relative to the API base URL.

| Removed operation | Use instead |
| --- | --- |
| `renameSlug` (`PATCH /workspaces/{workspaceSlug}/slug`) | None. Old workspace addresses keep redirecting to the current one. |
| `getTeamSettings`, `getLabelFilters`, `getRepositorySettings` | `getAllTeams` (`GET /workspaces/{workspaceSlug}/team`) |
| `updateTeamSettings` | `updateTeamVisibility` (`POST /workspaces/{workspaceSlug}/team/{id}/visibility`) |
| `addLabelFilter` | `addLabelToTeam` (`POST /workspaces/{workspaceSlug}/teams/{teamId}/labels/{repositoryId}/{label}`) |
| `removeLabelFilter` | `removeLabelFromTeam` (`DELETE /workspaces/{workspaceSlug}/teams/{teamId}/labels/{labelId}`) |
| `updateRepositorySettings` | `updateRepositoryVisibility` (`POST /workspaces/{workspaceSlug}/team/{teamId}/repositories/{repositoryId}/visibility`) |
| `getMembership` | `listMemberships` (`GET /workspaces/{workspaceSlug}/members`), or `getOwnMembership` (`GET /workspaces/{workspaceSlug}/members/me`) for your own membership |
| `reorderPractices` | None |
| `getReviewBatch` | `listReviewBatches` (`GET /workspaces/{workspaceSlug}/practices/review-batches`) |
| `getReviewEvidenceOutcomes` | None |
| `getReviewUsage` | The `usage` field of `getReview` (`GET /workspaces/{workspaceSlug}/practices/reviews/{reviewId}`) |
| `listReviewPracticeScripts` | The `practices[].script` field of `getReview` |
| `getReviewItem` | The `item` field of `getItemFacts` (`GET /workspaces/{workspaceSlug}/practices/items/{itemId}/facts`) or `getItemTrace` |
| `listItemEvents` | The `events` field of `getItemTrace` (`GET /workspaces/{workspaceSlug}/practices/items/{itemId}/trace`) |
| `getOutlineCollection` | `listOutlineCollections` (`GET /workspaces/{workspaceSlug}/outline/collections`) |
| `adminGetLlmConnection`, `adminGetLlmModel` | `adminListLlmConnections` (`GET /admin/llm/connections`), `adminListLlmModels` (`GET /admin/llm/models`) |
| `workspaceGetLlmConnection`, `workspaceGetLlmModel` | `workspaceListLlmConnections` (`GET /workspaces/{workspaceSlug}/llm/connections`), `workspaceListLlmModels` (`GET /workspaces/{workspaceSlug}/llm/models`) |

#### 🔴 GitHub Discussions and Projects V2 are removed

Hephaestus no longer synchronizes GitHub Discussions or Projects V2.
The upgrade drops their nine mirror tables and removes their activity entries.
It also removes the Discussions sync cursor and timestamps and the repository capability flag.
GitLab merge request threads and GitLab projects (repositories) remain supported.

Before upgrade:

1. Stop all Hephaestus runtime roles.
2. Back up PostgreSQL and verify a restore. Follow [Backup and restore](https://docs.hephaestus.build/admin/backup-restore).
3. Remove `HEPHAESTUS_SYNC_DISCUSSIONS_ENABLED`, `HEPHAESTUS_SYNC_PROJECTS_ENABLED`, `hephaestus.sync.discussions`, `hephaestus.sync.projects`, and `hephaestus.sync.filters.allowed-projects` from deployment configuration.
4. Remove the GitHub `discussions` and `organization_projects` permissions, the classic token scope `read:project`, and the `discussion`, `discussion_comment`, `projects_v2`, `projects_v2_item`, and `projects_v2_status_update` event subscriptions. Remove organization webhooks used only for those events.
5. Upgrade all runtime roles together, then start them.

The migration does not copy or retain the removed content.
A schema rollback cannot recreate it. To recover it, stop all roles, restore the verified pre-upgrade database backup, and start the previous release against that database.

#### 🔴 Stop every role and export survey results before upgrading

Before you upgrade, open **Instance administration → Product feedback → Surveys** in the current release. Open each survey’s results and download its CSV export. Store the exports under your retention and access rules for personal data.

Stop all roles before the upgrade. The database migration permanently deletes survey definitions, answers, declines, invitation records, survey notification subscriptions, and pending survey events. There is no in-place rollback. Recovery requires a verified pre-upgrade database backup and the previous release.

Product feedback submissions and the private admin inbox remain available. Research consent and practice research remain available. Existing research grants and refusals remain valid for the same organization. Removing survey answers narrows the existing scope and adds no new purpose, so this upgrade requires no renewed consent. New consent decisions record the revised wording.

Pre-upgrade backups and CSV exports still contain survey answers. Keep them under your retention and access rules until they expire.

#### 🔴 Remove settings that 1.0 no longer reads

This release removes switches and alternative names for settings.
Before you upgrade, make these changes in your Helm values and environment.

| Remove or change | Action |
| --- | --- |
| Helm value `config.gitlab.workspaceCreation` and env `GITLAB_WORKSPACE_CREATION` | Remove them. GitLab workspace creation is available whenever GitLab is enabled (`GITLAB_ENABLED`). The chart refuses the old Helm value. |
| `SLACK_OAUTH_CLIENT_ID`, `SLACK_OAUTH_CLIENT_SECRET` | Set `HEPHAESTUS_INTEGRATION_SLACK_CLIENT_ID` and `HEPHAESTUS_INTEGRATION_SLACK_CLIENT_SECRET` instead. The Slack login provider reads only these names. |
| `GH_AUTH_TOKEN` as `hephaestus.integration.github.meta.auth-token` | Nothing reads that key. `GH_AUTH_TOKEN` still sets `hephaestus.contributors.github.auth-token`. |
| `SPRING_PROFILES_ACTIVE=webhook-server` or `worker-node` | Use `prod,webhook` or `prod,worker`. The chart already does. The old names now activate nothing, so the process would start without the production profile. |
| `hephaestus.integration.github.enabled`, `hephaestus.mentor.slack.projector.enabled` | Remove them. Nothing reads them. |

`WEBHOOK_EXTERNAL_URL`, `HEPHAESTUS_WEBAPP_URL` and `GITLAB_DEFAULT_SERVER_URL` still work.
When they are unset, they take the application URL or the GitLab login URL from the setting itself, not from a second environment variable.

The runtime role now decides whether a process uses NATS: the server and webhook roles do, and the worker role does not.
Remove `NATS_ENABLED` and `HEPHAESTUS_SYNC_NATS_ENABLED`.
The chart now passes the broker address to the webhook role as `NATS_SERVER`, like the server role.

#### 🔴 Stop every role before the review lease cutover

Stop all roles before the 1.0 upgrade. Apply the database migration before you start the new release.
Practice review attempts now use a lease ID for each attempt. The migration removes the worker ID from these rows.
A review that was running when you stopped the previous release gets a lease that no worker holds. When that lease expires, the review runs again.
Restore the verified pre-upgrade database backup before you start the previous release.

### v0.95.1

#### 🔴 Update the installed reconciler before the 1.0 chart upgrade

Before promoting 1.0, install the verified 0.95.1 reconciler and its units from `deploy/reconcile/`.
Follow [Pull-based deployment](https://docs.hephaestus.build/admin/pull-based-deployment#follow-a-channel-with-the-chart) for the one-time tooling update.
The 0.95.0 reconciler cannot apply a release channel with the chart.

For 0.95.x → 1.0, stop the writers with the verified source chart first.
The reconciler starts the target server with workers stopped.
It does not roll back a failed migration.
Follow the stopped upgrade and database recovery procedures before restoring the worker count.

### v0.95.0

#### 🔴 Admin passkey protection

Production instances now require passkey verification before instance administration. OAuth still establishes account identity. Admin accounts without a passkey can enroll through User settings but cannot use instance administration until verification succeeds.

Before upgrade, keep deployment and database access available. After upgrade, register a passkey, verify it, register a second passkey on another device, and save recovery codes. Test recovery before you depend on it.

If the SPA uses another origin, configure `HEPHAESTUS_AUTH_PASSKEYS_ALLOWED_ORIGINS` and `HEPHAESTUS_AUTH_PASSKEYS_RP_ID`. Use exact trusted HTTPS origins. An RP ID change makes existing passkeys unusable.

To make instance-admin passkeys optional, explicitly set `HEPHAESTUS_AUTH_PASSKEYS_INSTANCE_ADMIN_REQUIRED=false`. This weakens protection. Workspace and personal requirements still apply.

The migration adds account-owned credential, challenge, and recovery-code tables, account protection fields, and a workspace policy field. Keep these tables in encrypted backups. Recovery codes permit credential replacement, not direct admin access.

#### 🔴 Update the Slack agent description

Paste the agent description from the Slack app manifest template into your Slack app settings. It now says: "Ask Hephaestus about your practice feedback and recent work in private Slack DMs."

#### 🔴 Reach the broker only from its own host

The multi-file Compose stack now publishes the broker's client port 4222 on `127.0.0.1` only.
The `NATS_BIND_HOST` setting is no longer read.
A stack on the same host connects over the Docker network `shared-network` to `nats-server:4222`, as preview environments do.

**Operators:** do these steps only if you set `NATS_BIND_HOST`.

1. Remove `NATS_BIND_HOST` from the environment file of the `core` stack.
2. Remove any firewall rule that you added to open port 4222.
3. Move a consumer that ran on another host onto the broker's host, and attach it to `shared-network`.

#### 🔴 Update the restore lockdown path

The lockdown SQL now ships inside the signed Helm chart.
In a source checkout, its path is `deploy/chart/hephaestus/files/restore-clone-lockdown.sql`.
In a verified release chart, use `chart-$VERSION/hephaestus/files/restore-clone-lockdown.sql`.
Update any restore script that reads the earlier `docker/self-host/restore-clone-lockdown.sql` path.

On Kubernetes, stop all application replicas and set `migration.enabled=false` before starting a restored database.
Apply the SQL with `ON_ERROR_STOP=1`, then verify silent mode and paused feedback.
Keep provider and model egress denied independently of silent mode.
Enable migration and start the server alone only after those checks pass.
The [restore procedure](https://docs.hephaestus.build/admin/kubernetes#restore) gives each phase.

#### 🔴 Stop every role before the source contract 3.0.0 upgrade

Before reviews resume after the upgrade, update custom scripts and criteria that read these fields from `metadata.json`:

- Read `head_checks`, `head_check_runs`, and `merge_state_status` from `checks.json`, under `scm.pull-request.checks`.
- Read `review_decision` from `review_threads.json`, under `scm.review-threads`.

The upgrade migrates current workspace practices, catalog overrides, and their comparison bases from contract 2.0.0 to 3.0.0. It preserves custom criteria and scripts. Practices that read pull request details gain optional reads for the two separate sources. Historical practice versions stay unchanged. The runtime accepts only contract 3.0.0 for a new capture.


Use the [stopped 0.95.0 upgrade procedure](https://docs.hephaestus.build/admin/upgrade). To reverse this migration, restore the database backup taken before the upgrade.

Queued reviews and unfinished scans without results receive new pins for their original definitions. Interrupted jobs recapture evidence and do not spend a retry. A review or scan with recorded results, reused results, or staged output stops with `CONTRACT_CHANGED`. Its results and historical version references stay unchanged. After the upgrade, request a new review or scan for the remaining work. This is an upgrade interruption, not feedback about the work.

The `mergeLead(record, merge, label)` script API stays unchanged. Adopted catalog scripts can keep their calls.

#### 🔴 Stop every role before the 0.95.0 database migration

Under the `prod` profile, runtime roles do not migrate the database at startup.
The server and worker wait for every database change set of their release.
The wait ends after `hephaestus.database.migration-wait` (`HEPHAESTUS_DATABASE_MIGRATION_WAIT`, default `10m`).
A role then stops and names the first change set that has not run.

Stop every previous-version runtime role before the 0.95.0 migration.
Keep a verified database backup and the matching release lock.
The bundled Compose stacks contain a one-shot `database-migration` service.
Run `docker compose run --rm -T --no-deps database-migration` while the old roles are stopped.
Start the new roles only after it exits successfully.

If you start the application image outside the bundled Compose stacks, use these steps:

1. Run the application image once with `SPRING_PROFILES_ACTIVE=prod,migrate`.
   Give it `DATABASE_URL`, `DATABASE_USERNAME` and `DATABASE_PASSWORD`, as the runtime roles receive them.
   It exits with status 0 when the migration completes.
2. Start the server, worker and webhook roles of that release after it completes.
3. If a role has `SPRING_LIQUIBASE_ENABLED=true`, remove that override.

An image rollback cannot reverse the schema upgrade.
Use the [failed-migration recovery procedure](https://docs.hephaestus.build/admin/upgrade#if-migration-fails).
See [Database migrations](https://docs.hephaestus.build/admin/runtime-roles#database-migrations) for the role contract.

#### 🔴 Update practice comparison API clients

The workspace comparison again counts developers by standing. Its tiles show the selected time window, the current developer's figures and the workspace's typical range.

Update clients that consume `/practices/workspace-overview` or `/practices/tiles` to the response schemas in this release's OpenAPI specification. The web app includes the generated client for these schemas.

Feedback mode controls delivery. A practice reviewed by Hephaestus remains eligible for standing when feedback is off. A practice reviewed by People or Nobody is excluded from these standings.

#### 🔴 Stop every role for the Hephaestus conversation upgrade, which deletes Hephaestus chat history

This release gives each developer one Hephaestus conversation in each workspace.
One migration drops the Hephaestus chat tables of the previous release: `chat_thread`, `chat_message`, `chat_message_vote` and `mentor_slack_thread`.
A runtime role of the previous release fails when it reads these tables.
Old and new runtime roles cannot use the database together.
There is no rolling upgrade or compatibility period.

Read these instructions before you start any runtime role of this release.
This procedure takes precedence over normal startup.

**What the upgrade deletes.**

- All earlier Hephaestus chat history: web chats, the history of Slack direct messages in Hephaestus, and votes.
  Slack keeps its own copy of the direct messages.
- The link from feedback to the Hephaestus chat message that showed it.
  The migration keeps every feedback row and its state.
  Feedback that Hephaestus showed in a chat stays delivered, but its conversation copy no longer points to a message.

There is no Liquibase rollback for this migration.
The pre-upgrade backup is the only way back to the previous release with its Hephaestus chat history.

**Before you upgrade:**

1. Drain and stop every role: `application-server`, `application-worker` and `webhook-server`.
   The [role drain procedure](https://docs.hephaestus.build/admin/runtime-roles#graceful-drain) applies to the worker.
2. Make a database backup ([Backup and restore](https://docs.hephaestus.build/admin/backup-restore)).
3. Verify that the backup restores.
4. Record the signed release lock and the configuration of the previous release.
   A restore needs this lock, so that the failed release does not migrate the restored database again.

A provider can lose events that arrive while the webhook role is stopped.
Keep the outage short.

**Upgrade:**

1. Apply the changes under **Configuration** below.
2. Install this release for the server, worker and webhook roles.
3. Use the agent image of this release.
   If you pin a custom agent image, rebuild it from the matching source.
   Its `hephaestus.agent.runtime-contract` label must be `9`.
4. Run the [database migration step](https://docs.hephaestus.build/admin/runtime-roles#database-migrations) with this release while every runtime role stays stopped.
   Continue only after the migration completes successfully.
   On Kubernetes, follow [Stopped schema upgrades](https://docs.hephaestus.build/admin/kubernetes#stopped-schema-upgrades), with all three replica counts at zero through migration success and no automatic rollback.
5. Start the server, worker and webhook roles.
6. Update the Slack app, as **Slack app** below tells.

**If the migration fails.**
Keep every role stopped.
Preserve the migration command output and the first failed change set before you retry.

Do not start the previous release against this database.
Choose one recovery path:

1. Apply a forward fix that is qualified for the recorded migration state.
2. Restore the verified backup with its matching signed release lock and configuration.
   Follow [failed-migration recovery](https://docs.hephaestus.build/admin/upgrade#if-migration-fails).
   The backup does not contain data that was recorded after the upgrade.

**Slack app.**
Update the app from `docs/admin/slack-app-manifest-template.yml`.
The manifest adds the bot scope `reactions:write` and removes the `app_context_changed` event.
Reinstall the app in the Slack workspace.
Then select **Reconnect Slack** in each Hephaestus workspace that uses it.
Until then, Hephaestus cannot add 👀 to a question.
See [Update an existing Slack app](https://docs.hephaestus.build/admin/production-setup#update-an-existing-slack-app).

**Configuration.**

- With Slack enabled, the application server now answers Slack interactivity, the App Home and Hephaestus buttons.
  It commits a button's consent change before Slack gets its answer.
  The webhook role does not answer `/webhooks/slack/interactivity`; it keeps `/webhooks/slack` for Events.
  - Give `HEPHAESTUS_INTEGRATION_SLACK_SIGNING_SECRET` to `application-server` as well.
    With Slack enabled and the secret blank, `application-server` does not start.
    `docker/compose.app.yaml` passes it.
  - Route the exact path `/webhooks/slack/interactivity` to `application-server`, without stripping it.
    The route must outrank the `/webhooks` route of `webhook-server`.
    `docker/compose.app.yaml` adds this route (`https-slack-interactivity`, priority 110).
    A custom proxy needs the same route.
    If the Slack app's **Interactivity request URL** names a separate webhook host, point it at a host that reaches `application-server` ([Slack app configuration](https://docs.hephaestus.build/admin/production-setup#slack-app-configuration)).
- Remove the Traefik sticky-cookie labels (`__Secure-hep_workspace_aff`) from a custom proxy configuration.
  `docker/compose.app.yaml` does not have them.
  The `X-Hephaestus-Replica` response header is removed.
- Remove these settings and their `HEPHAESTUS_MENTOR_*` environment variables.
  This release does not read them:
  - `hephaestus.mentor.idle-ttl-seconds`, `grace-timeout-seconds`, `reap-interval-seconds` and `ring-buffer-frames`.
  - `hephaestus.mentor.stdin-write-timeout-ms`, `send-queue-capacity`, `subscriber-queue-capacity` and `attach-first-frame-timeout-seconds`.
  - `hephaestus.mentor.max-sessions-per-user`, `max-sessions-total` and `max-frame-bytes`.
  - `hephaestus.mentor.in-flight-reaper.window` and `hephaestus.mentor.in-flight-reaper.cron`.
- Rename the Hephaestus rate limit `hephaestus.auth.rate-limit.mentor-chat` to `mentor-turns`.
  Rename `HEPHAESTUS_RATE_LIMIT_MENTOR_CHAT_CAPACITY` to `HEPHAESTUS_RATE_LIMIT_MENTOR_TURNS_CAPACITY`.
  Rename `HEPHAESTUS_RATE_LIMIT_MENTOR_CHAT_PERIOD` to `HEPHAESTUS_RATE_LIMIT_MENTOR_TURNS_PERIOD`.
  This release does not read the old names, so a changed limit goes back to its default until you rename it.
- The **Internet access** switch of the Hephaestus model assignment is removed.
  The `allowInternet` field of the agent-binding API and the `workspace_agent_binding.allow_internet` column are removed too.
  In the previous release, the switch gave Hephaestus's sandbox internet access.
  The Hephaestus host runs on an internal network with no internet access.
  [Runtime roles](https://docs.hephaestus.build/admin/runtime-roles#the-conversation-host) gives the one exception: a worker that runs outside a container.
- The Hephaestus API under `/api/workspaces/{slug}/mentor/` changed.
  The `chat`, `sandbox` and `threads` endpoints are removed.
  Regenerate a custom API client from the `server/openapi.yaml` of this release.
- [Tunables](https://docs.hephaestus.build/admin/runtime-roles#tunables) lists the new `hephaestus.mentor.turn.*` settings.
  All roles must have the same values.

**Worker and Hephaestus host.**
In the previous release, a worker started one Hephaestus sandbox for each developer and workspace.
In this release, each worker starts one Hephaestus host container when it claims its first Hephaestus turn.
That host runs all Hephaestus turns of the worker.
It has a fixed limit of 2 GiB of memory ([The Hephaestus host](https://docs.hephaestus.build/admin/runtime-roles#the-conversation-host)).
The recommended single host increases from 16 GB to 24 GB of memory ([Install](https://docs.hephaestus.build/admin/install#requirements)).

- `HEPHAESTUS_WORKER_MENTOR_MAX` (`hephaestus.worker.capacity.mentor-max`) sets how many Hephaestus turns the Hephaestus host of one worker runs at the same time.
  Its `auto` default is 8.
  In the previous release, it limited the Hephaestus sandboxes of one worker, and `auto` was half the CPUs.
  [Hephaestus capacity](https://docs.hephaestus.build/admin/runtime-roles#conversation-turn-capacity) tells how to size it.
- With the Docker sandbox runtime, a worker in a container that it cannot identify starts no Hephaestus host, and its Hephaestus turns fail.
  [Docker configuration](https://docs.hephaestus.build/admin/configuration-readiness#docker-configuration) gives the hostname rule.
- Hephaestus reads context documents, code and Git history in turn sandboxes on the worker, not in the Hephaestus host.
  With the Docker sandbox runtime, each worker keeps 2 empty turn sandboxes and starts one for each running Hephaestus turn that reads.
  Each has 1 CPU, 1 GiB of memory and no network ([Turn sandboxes](https://docs.hephaestus.build/admin/runtime-roles#turn-sandboxes)).
  With Kubernetes, use the chart's [sandbox sizes](https://docs.hephaestus.build/admin/kubernetes#sandbox-sizes).
  Hephaestus reads repositories only where Git mirrors are enabled (`GIT_CHECKOUT_ENABLED`).

**Models.**

- Hephaestus refuses a model whose stated context window is below 32,768 tokens or whose output limit is below 4,096 tokens.
  Check the Hephaestus model assignment of each workspace ([AI providers](https://docs.hephaestus.build/admin/ai-providers#the-smallest-conversation-model)).
- A Hephaestus turn fails when its model provider sends no response headers for 5 minutes, or no byte of its stream for 2 minutes.
  SSE keep-alive comments count as bytes.
  `HEPHAESTUS_MENTOR_TURN_MODEL_FIRST_BYTE_TIMEOUT` and `HEPHAESTUS_MENTOR_TURN_MODEL_IDLE_TIMEOUT` change these bounds.
  A provider gateway that buffers whole answers must stream them or send keep-alive comments.
  Otherwise, set the bounds longer than the longest answer.
- Each worker pauses a provider connection for 60 seconds when its calls keep stalling or failing.
  Watch `resilience4j_circuitbreaker_state{name=~"llm-connection-.*"}`.

**Metrics.**
Update dashboards and alerts that use these series.
[Hephaestus turn metrics](https://docs.hephaestus.build/admin/observability#conversation-turn-metrics) lists the series of this release.

- These series are removed: `mentor_attach_*`, `mentor_session_*`, `mentor_relay_*`, `mentor_turn_runtime_ready_seconds`, `mentor_frame_parse_error_total`, `mentor_in_flight_reaper_failure_total`, `mentor_ring_buffer_dropped_total`, `mentor_send_frame_bytes`, `mentor_send_rejected_total`, `mentor_subscriber_dropped_total`, `mentor_subscriber_error_total`, `mentor_watchdog_targets` and `llm_proxy_unbillable_refused_total`.
- The `worker_capacity_*{type="mentor"}` series are removed. Use `mentor_host_turns_active` for the Hephaestus turns that a worker runs.
- The `sandbox` label of `mentor_turn_first_token_seconds` is removed.
- `mentor_turn_cost_usd_USD` is renamed to `mentor_turn_cost_usd`, with the same `_count`, `_sum` and `_max` series.
- `mentor_turn_completed_total{outcome}` has the values `success`, `stopped`, `failed` and `lease_expired`.
  The values of the previous release, such as `client_disconnect`, `poisoned`, `timeout` and `rejected`, are removed.
- `mentor_turn_started_total` counts each attempt that a worker claims, also a retry and an attempt that admission declines.
  In the previous release, it counted each question when a server dispatched it.
- `mentor_turn_first_token_seconds` starts when an attempt starts on a worker.
  In the previous release, it started when a server accepted the question.
- New series: `mentor_turn_retried_total`, `mentor_turn_queued`, `mentor_turn_queue_wait_seconds`, `mentor_turn_lease_reaper_failure_total`, `mentor_turn_claim_failure_total`, `mentor_host_ready`, `mentor_slack_reply_total`, `mentor_live_streams` and `llm_proxy_upstream_stalled_total`.

#### 🔴 Custom practice scripts return results without coverage

Hephaestus now measures the coverage of every practice script result. A custom script that returns a result with a `coverage` field stops as an invalid result, and its practice records no observation.

Before you upgrade, remove `coverage` from each result that your custom scripts build in code. Read in full each source that the result cites: a citation alone shows only the quoted lines. Scripts that return what `ctx.review` returned need no change. The bundled practices need no change.

#### 🔴 The multi-file Compose proxy needs ACME_EMAIL

The proxy stack of the multi-file Compose stack (`docker/compose.proxy.yaml`) no longer sends a fixed contact address to Let's Encrypt. It reads `ACME_EMAIL`, and it does not start without it.

Before you upgrade, set `ACME_EMAIL` to the address that gets certificate-expiry notices in the environment file of the proxy stack. The single-host stack and the Helm chart are not affected.

#### 🔴 Disable host swap before the one-host upgrade

Disable swap devices and files, and remove their boot activation before running `k3s-install.sh`.
The installer rejects active swap, and the kubelet now requires `failSwapOn=true` with `NoSwap`.

The installer verifies the running kubelet policy and Node allocatable after k3s starts.
If verification fails, correct conflicting k3s flags or kubelet drop-ins before retrying.
Use `sh k3s-install.sh --verify-kubelet` with the installation kubeconfig to repeat the read-only cluster checks.

The installer accepts the Ubuntu 24.04/kernel 6.8 GA baseline.
Use `--allow-unqualified-host` only for an authorized qualification of another host family.
Ubuntu 26.04/kernel 7 needs an install, repeat-install, and pressure rehearsal with the exact release assets before target-host rollout.
The override keeps effective-policy and allocatable verification active.
Soft eviction stays disabled until stateful-service request sizing and admission protection are qualified.

#### 🔴 Legal pages come only from your mounted files

The webapp image no longer bundles a legal profile, and the webapp no longer reads `LEGAL_PROFILE`.
`/imprint` and `/privacy` show the Markdown files that you mount at `/usr/share/nginx/html/legal-overrides/`.
Without them, the webapp shows the built-in "not configured" placeholder with a red banner.

The chart refuses `webapp.legalProfile`. Before you upgrade, remove `webapp.legalProfile` from your Helm values, even if it is empty.

If you used a legal profile, also do this before you upgrade:

1. Write `imprint.md` and `privacy.md` for your deployment.
2. On the Helm chart, put them in a ConfigMap in the release namespace and name it in `webapp.legalOverrides`, as [Legal pages](https://docs.hephaestus.build/admin/legal-pages#deployment-specific-overrides) describes.
   Outside the chart, mount them read-only at `/usr/share/nginx/html/legal-overrides/` in the webapp container, and remove `LEGAL_PROFILE` from its environment.
3. After the upgrade, open `/imprint` and `/privacy` and confirm that they show your pages.

#### 🔴 Prepare for the interfaces that 1.0 removes

This release deprecates settings and interfaces for removal in 1.0.
It does not remove them.
The release note **0.95.0 is the last 0.x release** lists each affected setting, value and endpoint.
Use that list to check your deployment and API callers before 1.0.
It includes `reasoningEffort` and `clearReasoningEffort` on both model-update requests.

Upgrade a Compose install to 0.95.0 before you move it to the chart at the same version.
Keep its PostgreSQL and JetStream backups, encryption keys, values and signed release lock.
Then follow [Upgrade from 0.x to 1.0](https://docs.hephaestus.build/admin/upgrade-to-1-0).
Do not start a 0.x runtime role on the 1.0 schema.

The release note also lists 21 public operations that 1.0 removes.
Update any automation that calls those exact methods and paths.
The immediate scan action and membership removal stay available.
A removed HTTP wrapper does not remove its underlying records.

The same release note lists all conversation renames for the 1.0 upgrade.
Update configuration, dashboard queries, spans and API callers with those exact replacements.
The list includes HTTP paths, operation IDs, schema names, DTO fields and enum values.
`FREE_HEPH` becomes `CAPACITY` only in 1.0. The field name stays `waitingFor`.
The old web-page redirect and Slack message identifiers remain as stated in that notice.

In 1.0, personal-data exports change `notification_subscription.kind` from `MENTOR_DIGEST` to `WEEKLY_DIGEST`.
For `notification.email.delivery`, update the `kind` tag from `mentor_digest` to `weekly_digest`.
For the auth rate-limit blocked metric, update the `bucket` tag from `mentor-turns` to `conversation-turns`.
Unsubscribe links stay unchanged because they use persisted UUIDs.

#### 🔴 Rename a workspace that uses the `conversation` slug

`conversation` is reserved for the conversation route. Before you upgrade, rename any workspace with this slug in workspace settings. The migration stops with an instruction to rename the workspace if the slug is still in use. It does not rename a workspace automatically.

#### 🔴 Deploy matching runtime roles for retained review evidence

Drain and stop the runtime roles before the database upgrade.
Use the [role drain procedure](https://docs.hephaestus.build/admin/runtime-roles#graceful-drain).
Make and verify a database backup first.
Keep the signed release lock and configuration with the backup.
Deploy matching server, worker and webhook images.

The additive migration stores bounded original evidence on new pull-request, merge-request and submitted-review events.
It also adds an unavailable-original decision reason.
An earlier server or worker cannot read that new reason.
Do not mix old and new runtime versions against this database.
Return to an earlier release only through the [compatible-backup restoration procedure](https://docs.hephaestus.build/admin/upgrade#rollback).

Events from earlier releases have no retained original.
Their event-driven reviews are withheld if the original is needed.
No migration reconstructs that original from current work.
Existing observations and feedback stay unchanged.
An explicit new request can review the current work under its normal checks.

The [personal data map](https://docs.hephaestus.build/admin/dsms/personal-data-map) owns retention and erasure of the new evidence.

#### 🔴 Keep Docker `SANDBOX_MEMORY_BYTES` at 2.6 GiB or more

Docker review sandboxes now use `SANDBOX_MEMORY_BYTES`, `SANDBOX_CPUS` and `SANDBOX_PIDS_LIMIT`.
Before this release, these settings had no effect.
The memory limit must hold the sandbox's temporary file systems, which can grow to 2.06 GiB, plus 512 MiB for its processes.
The worker refuses to start if `SANDBOX_MEMORY_BYTES` is smaller.

**Operators:** do this step only if you use the Docker sandbox runtime and set `SANDBOX_MEMORY_BYTES` yourself.

1. Set `SANDBOX_MEMORY_BYTES` to 2751463424 or more, or remove it to use the default of 3 GiB.

On Kubernetes, remove `SANDBOX_MEMORY_BYTES` and use the chart's [sandbox sizes](https://docs.hephaestus.build/admin/kubernetes#sandbox-sizes).

#### 🔴 Grant the worker `watch` on ResourceQuotas if you manage its Role yourself

The worker now watches the ResourceQuotas of the sandbox namespace, as it watches its Pods and Jobs. The chart's Role grants `list` and `watch` on `resourcequotas`, and no longer `get`, so an install that uses the chart's Role needs no action.

If you replaced the worker's Role in the sandbox namespace with your own, add `watch` on `resourcequotas` before you upgrade. Without it, the worker logs that a sandbox namespace informer failed with status `403`, and it stays not ready until the verb is granted.

#### 🔴 Remove the hand-set sandbox quota values

The chart now derives the sandbox quotas from `worker.replicas`, `worker.reviews` and `worker.conversationTurns`: one quota for review sandboxes and one for the Hephaestus host and turn sandboxes. It no longer reads `worker.sandbox.quota.pods`, `worker.sandbox.quota.cpu` or `worker.sandbox.quota.memory`, and its values schema refuses them.

Before you upgrade, delete these three keys from your values file. Keep `worker.sandbox.quota.enabled`. To run more reviews or Hephaestus turns at once, raise `worker.reviews` or `worker.conversationTurns`: the quotas follow.

The quotas hold exactly what the sandboxes request, and a quota also counts a RuntimeClass's Pod overhead. If `worker.runtime.runtimeClassName` names a class with an `overhead.podFixed`, such as gVisor or Kata on some clusters, set `worker.runtime.podOverhead.cpuMillis` and `worker.runtime.podOverhead.memoryMi` to it. Otherwise the quota refuses the worker's isolation probe, and the worker starts no sandbox.

`priorityClasses.reviews` and `priorityClasses.conversations` must name different classes. The chart refuses to render if they are the same.

If you manage the quotas yourself (`worker.sandbox.quota.enabled: false`), a quota that selects the review PriorityClass, or that has no scope, bounds how many reviews a worker claims.

#### 🔴 Encrypt the storage of sandbox nodes

On Kubernetes, sandboxes keep their temporary files on the node's disk instead of in memory.
A review's repository was on the node's disk already.
Encryption protects a stolen disk and a backup of it without the key.
It does not protect against a compromised running node or a privileged workload on the node.

**Operators:** before you upgrade, make sure that the disk of each node that runs sandboxes is encrypted, for example with LUKS or with the disk encryption of your cloud provider ([Kubernetes § Sandbox prerequisites](https://docs.hephaestus.build/admin/kubernetes#sandbox-prerequisites)).

#### 🔴 Leave room for the new PostgreSQL memory limit

The bundled Compose stacks now limit PostgreSQL to 4 GiB (`POSTGRES_MEM_LIMIT`).
The image sizes `shared_buffers` and its other memory settings from that limit when it starts.
Before this release, PostgreSQL had no limit and used PostgreSQL's small defaults.
The upgrade restarts PostgreSQL once to apply the limit.

**Operators:** do this step only if the host has less than 4 GiB free beside the other containers.

1. Before the upgrade, set `POSTGRES_MEM_LIMIT` in `.env` to the memory that PostgreSQL can use, for example `2g`.

See [PostgreSQL memory](https://docs.hephaestus.build/admin/sizing#postgresql-memory).

#### 🔴 Keep the existing broker budget before you apply the small profile

The `small` profile now uses an 8 GiB broker file budget and a 10 GiB claim for fresh storage.
For an existing installation, set `nats.jetstream.maxFileBytes` to its current value in your site values before you upgrade.
Do not shrink a PVC or change an immutable StatefulSet claim template.
Do not lower the broker budget below its stored data.

The profile sets GitHub to 4 GiB and each other provider to 512 MiB, with a maximum age of seven days.
Before you shorten those bounds, verify an off-host backup and test its restore.
Follow [Changing a bound on a stream that already exists](https://docs.hephaestus.build/admin/webhook-ingestion-operations#changing-a-bound-on-a-stream-that-already-exists).
Keep `webhook.stream.allowDestructiveLimitUpdates` false until you approve the data removal.
Enable it only for that change, then return it to false.
A higher limit cannot recover deleted messages.

The server keeps 30-day durable expiry. It reconciles only consumers it uses.
Review orphan consumers separately. A recreated consumer starts with new messages.

#### 🔴 Update log queries for the new log format and remove `SENTRY_DSN`

The server, worker and webhook containers now write Elastic Common Schema JSON through Spring Boot's own formatter.
Hephaestus no longer sends errors to Sentry, from the server or from the browser.

**Operators:**

1. Remove `SENTRY_DSN` from the environment of every container. The new release ignores it.
2. Update saved log queries, alerts and dashboards for these field changes:

   | Before                 | After                                      |
   | ---------------------- | ------------------------------------------ |
   | `timestamp`            | `@timestamp`                               |
   | `level`                | `log.level`                                |
   | `logger`               | `log.logger`                               |
   | `mdc.<name>`           | `context.<name>`, for example `context.traceId` |
   | `<event field>`        | `context.<event field>`, for example `context.event.name` |
   | `stacktrace`           | `error.stack_trace`, with each exception named by its class only |
   | `mdc.workspace_slug`   | Removed. Use `context.workspace.id` |
   | NATS failure `subject` | `context.integration.route` (provider and event) and `context.integration.delivery` |
   | Exception message text in a log message | The exception class |

   Each line also carries `service.name`, `service.version`, `service.environment`, `service.node.name` and `runtime.role`.
   Dotted names are nested JSON objects.
   Read them with a JSON tool, not by text position.
   A stack trace is at most 8192 characters and 32 frames for each exception.
3. Error reports that an earlier release sent to Sentry stay with that recipient under its own retention.
   Removing the integration does not delete them.
4. Keep production logging at `INFO`.
   `DEBUG` on framework, client or driver packages can write request and provider content.

To collect the logs, see [Collecting logs](https://docs.hephaestus.build/admin/observability#collecting-logs).
Collection is optional and sends nothing until you configure a destination.
Start it only after this release runs: lines that an earlier release wrote are not redacted after the fact.

#### 🔴 Stop every role for this upgrade, which drops unused stores

This release drops stores that the application no longer reads or writes:

- the `outline_document_event` table, the Outline document event log, which holds Outline editor ids;
- the `consent_notice` table (each consent decision keeps its notice version, and the wording of every version is in the release that published it);
- the `product_survey_submission` table, after the upgrade copies each submission that has no participation record yet;
- the `review_job.idempotency_key` column and its index `uk_review_job_active_key`;
- the `supports_reasoning` column of `llm_model` and `workspace_llm_model`;
- the `workspace_slug_history.redirect_expires_at` column, because rename redirects do not expire;
- the `review_snapshot_id` and `review_snapshot_digest` columns of `issue`;
- the `research_opted_out` and `source` columns of `slack_participant_consent`, after the upgrade copies each research opt-out of a linked Slack user into the consent decisions of the account.

A runtime role of the previous release fails when it uses these objects.
Stop every runtime role of the previous release before you start this release.
Back up the database first.
The upgrade deletes the rows of these stores.
A rollback of the migration stops and asks for the backup.

#### 🔴 Send webapp traffic to port 8080

The webapp container now runs nginx as UID 101 and GID 101, and listens on port 8080 instead of port 80.
It needs no added capabilities and writes only to `/tmp/hephaestus-webapp`.
The bundled Compose stacks already route to port 8080, including the maintenance page.
They run the container with a read-only root file system and a `tmpfs` at `/tmp/hephaestus-webapp`.

**Operators:** do these steps only if you run the webapp image outside the bundled Compose stacks.

1. Change your reverse proxy, load balancer and health check to send traffic to container port 8080.
   For `docker run`, change `-p <host port>:80` to `-p <host port>:8080`.
   If you serve your own nginx configuration from this image, change its `listen` port to 8080.
2. Mount an empty directory at `/tmp/hephaestus-webapp` that UID 101 can write.
   With Docker, use `--tmpfs /tmp/hephaestus-webapp:uid=101,gid=101,mode=0700,size=16m`.
   Then you can make the root file system read-only.
3. Remove the `CHOWN`, `SETGID` and `SETUID` capabilities if you added them for nginx.
   Remove a `--user 0` or `user: root` override.
4. Make your legal override files readable by UID 101.
   The mount path `/usr/share/nginx/html/legal-overrides` does not change.

See [Webapp container](https://docs.hephaestus.build/admin/runtime-roles#webapp-container).

#### 🔴 Replace the webhook publish timeout and retry count with one deadline

The webhook receiver no longer reads `HEPHAESTUS_WEBHOOK_PUBLISH_TIMEOUT` (`hephaestus.webhook.publish.timeout`) or `HEPHAESTUS_WEBHOOK_PUBLISH_MAX_RETRIES` (`hephaestus.webhook.publish.max-retries`). If you set either one, delete it: the receiver ignores it without an error.

The receiver now waits up to `HEPHAESTUS_WEBHOOK_PUBLISH_DEADLINE` (`hephaestus.webhook.publish.deadline`, default `8s`) for the event broker to store a delivery. The value must stay below `10s`, the time GitHub and GitLab wait for an answer. Set it only if you need another limit.

#### 🔴 Shorten the webhook streams to 7 days

Webhook streams now keep deliveries for 7 days.
A stream that already exists keeps its earlier 180 days, because startup does not delete stored messages on its own.
The webhook receiver then logs `Stream github limit change withheld because it would delete stored messages` at each start.

**Operators:** do these steps once, on each install whose streams existed before this release.

1. Check that `webhook.stream.consumer.pending` and `webhook.stream.consumer.ack.pending` are near zero.
   The messages that the new age deletes were then delivered already.
2. Allow the update for one start of the webhook receiver:
   - Docker Compose: set `HEPHAESTUS_WEBHOOK_STREAM_ALLOW_DESTRUCTIVE_LIMIT_UPDATES=true`.
     If you set `HEPHAESTUS_WEBHOOK_STREAM_MAX_AGE=180d`, change it to `7d` or remove it.
     Then restart the webhook receiver.
   - Helm chart: run `helm upgrade` with `--set webhook.stream.allowDestructiveLimitUpdates=true`.
     If you set `webhook.stream.maxAge: 180d`, change it to `7d` or remove it.
3. Look for `Reconciled JetStream stream limits and DELETED stored messages` in the log of the webhook receiver.
   The broker deletes the old messages before it answers. How long that takes on a large stream on a slow disk is unmeasured.
   If the log reports a timeout, raise `HEPHAESTUS_WEBHOOK_STREAM_LIMIT_UPDATE_TIMEOUT` (chart: `webhook.stream.limitUpdateTimeout`) and start the receiver again.
4. Turn the flag back off: `false` in the environment file, or `webhook.stream.allowDestructiveLimitUpdates=false` on the next `helm upgrade`.

#### 🔴 Workers no longer connect to the server

Workers and the server now share only the database. The worker hub, its registration token and its metrics are gone.

1. Delete `HEPHAESTUS_WORKER_REGISTRATION_TOKEN` and `HEPHAESTUS_HUB_URL` from your values, Secrets and environment files, and any `hephaestus.worker.hub.token.*`, `hephaestus.worker.control.*` or `hephaestus.worker.heartbeat.*` settings. Hephaestus ignores them.
2. Move alerts and dashboards off `worker.control.*`, `worker.heartbeats.*`, `worker.hub.*`, `worker.token.exchange` and `worker.jwt.verify`. `attempt.token.verify` counts the checks of sandbox credentials instead.

A practice review that still runs when you upgrade has no lease. The database migration counts it as a drain, and the server's lease recovery requeues it after its startup grace period, without spending a retry. Server maintenance runs even when `AGENT_ENABLED=false`.

The timeout backstop gives these claims a fresh timeout from the server's start, including the result-upload grace and safety buffer. If recovery does not finish in that time, the server ends the claim with a recorded upgrade failure and keeps its admitted results. The cutover uses one ordinary drain allowance. Rollback stops and requires the database backup taken before this upgrade.

#### 🔴 Select certificates on repeat one-host installations

If `site.yaml` exists, select certificates explicitly before a repeat install:

- Standard apex: `--standard-certificates`.
- Standard workspace hosts: `--workspace-subdomains --dns01-issuer NAME`.
- Custom certificates, including workspace hosts: `--certificates FILE`.

Use the correct public names and issuer. Omit `--staging` when you switch the standard certificates to the production issuer.

The installed manifest is root-only. Use `sudo` to copy it to a mode-0600 file that you own before reuse.

[Run the installer](https://docs.hephaestus.build/admin/kubernetes#2-run-the-installer) gives the copy command and choices.
The installer stops before host changes when no choice is explicit. A certificate preview does not change the host.

### v0.94.0

#### 🔴 Handle unavailable operator result text

Result summaries in the operator review API are null when source access cannot be verified. This applies to result lists and details, results linked to feedback, and lineage records. The detail also withholds the explanation and evidence in that state.

**Operators:** Update external API consumers to handle a null summary. The bundled interface displays the unavailable state and retains the result’s outcome, practice, work and delivery facts. No manual database change is required.

#### 🔴 Upgrade the practice model, scripts and clients together

This release changes practice definitions, people, reviews, results and feedback records in one migration.
Old and new runtime roles cannot use the database together.
There is no rolling upgrade or compatibility period.

Before the outage, test this release's migration on a verified database restore.
Follow the mandatory [pre-boot isolation procedure](https://docs.hephaestus.build/admin/production-operations-runbook#pre-boot-isolation--mandatory-for-every-restore) before restoring secrets or starting application code.
Complete the [pilot rehearsal and history checks](https://docs.hephaestus.build/admin/practice-model-cutover#rehearse-the-pilot-upgrade), including the outage measurements.
Prepare any script changes with the procedure under **Custom practice scripts**.
Repeat the test on a fresh restore until the migration completes.

Before the upgrade:

1. Pause [automatic reconciliation](https://docs.hephaestus.build/admin/production-operations-runbook#production-upgrade).
2. Quiesce new review submission and provider input.
3. Apply the script changes tested during the rehearsal through the previous release's server.
4. Drain active worker operations through the [role drain procedure](https://docs.hephaestus.build/admin/runtime-roles#graceful-drain).
5. Let active worker reviews finish.
6. Stop every worker role after its drain completes.
7. Drain and stop the server and webhook roles.
8. Make a database backup.
9. Restore the backup under the mandatory isolation procedure and verify the restore.
10. Record the release lock and configuration of the previous release.

A provider can lose events that arrive while the webhook role is stopped.
After restart, normal source sync repairs only the events that its adapter can recover.
Keep the outage short and verify source sync afterward.

Upgrade:

1. Install this release for the server, worker and webhook roles.
2. Use this release's verified agent image.
3. If HEPHAESTUS_IMAGE_AGENT_PI is overridden, update it to the matching agent-pi image.
4. Start only the server role under the [silent deployment checklist](https://docs.hephaestus.build/admin/production-operations-runbook#silent-deployment-checklist).
   That procedure requires egress isolation and silent-mode readback before reviews or provider ingress resume.
   Keep review execution disabled until the history checks pass.
5. Deploy the matching webapp.
6. Restore saved custom definitions with tested version 3 scripts.
7. Complete the [retained pilot history checks](https://docs.hephaestus.build/admin/practice-model-cutover#check-retained-pilot-history).
8. Ask developers to update the browser extension.
9. Update custom API clients before they use practice resources.
10. Start the worker and webhook roles only at the checklist's permitted stage.
11. Verify one controlled item review and one repository scan under **Prove silence** in that checklist.
12. Check results, withheld reasons and provider-copy receipts.

**If the migration stops at a precondition.**
Stop all remaining containers from the previous release before you retry.
A precondition failure does not undo earlier committed changesets.
Check the recorded changelog before retrying.
If the cutover has committed, use [failed-migration recovery](https://docs.hephaestus.build/admin/production-operations-runbook#if-migration-fails).

A recorded delivery lease or projection owner can remain after a container stops.
Keep new reviews and provider input paused.
Run the previous release's server until its delivery recovery settles those records.
Drain and stop it, then retry this release's server.
Do not clear delivery ownership or provider receipts by hand: the provider can already have accepted the feedback.

For any other refusal, use the [cutover diagnosis map](https://docs.hephaestus.build/admin/practice-model-cutover#diagnose-a-refused-cutover).
It identifies source records, supported recovery actions, and cases that need escalation with the previous release retained.

See [Removed settings and diagnostic data](https://docs.hephaestus.build/contributor/database-migration#removed-settings-and-diagnostic-data) for the data the cutover removes.

The migration keeps every migrated result as history, including its ID, text, citations, feedback relationships and provider receipts.
Every migrated result has evidence version 0, regardless of outcome.
It is not current proof and cannot establish current standing.
Use a fresh review to establish current standing.
Until then, practice pages and *Practices across the workspace* show no current results.
To restore standing for recent work at once, start a review of past work under *Practices › Review settings*.
Its estimate shows the work and the model cost before it starts.
History views can show retained text when source access and privacy checks permit it.

Multiple historical results for the same review, practice and item no longer stop the migration.
Their assignment is withheld with LEGACY_AMBIGUOUS_RESULTS.
The [legacy history reasons](https://docs.hephaestus.build/contributor/practice-review-glossary#legacy-history-reasons) define this state and ambiguous reuse.
The diagnosis map still applies to conflicting versions, tenants, contributors and receipts.
Existing retention can later remove ended results without feedback support.

Released review signals keyed by the pull request head (`sha~`) stay as pull request history.
They have no job and no review identity, so recorded ones are withheld with LEGACY_REVIEW_UNBOUND.

GitHub sync once created an issue stub from a comment and later turned it into its pull request.
Such a stub qualifies only if each issue fact on it is a finished "opened" event without a job.
Those facts stay on a removed issue item beside the pull request.
Any other disagreement between an item kind and the provider mirror still stops the migration.

The migration does not post migrated feedback again.
Historical unknown visibility stays Unconfirmed. A confirmed sibling copy keeps its delivery time.

**Practice settings.**
Practice definitions are stored on practice versions.
The new definition has reviewOn, appliesWhen, about, reads, reviewedBy, criteria, script, scan, lineNotes and guidance.
The source contract stays in reads.contract.
A customized practice linked to a bundled catalog entry keeps that entry's positive phrase when guidance.goingWell is null.
The migration preserves a supplied phrase and does not derive one from an unlinked practice's slug.
Human review explanations and limits stay in reviewedBy.
The role MERGER remains supported.

Feedback mode uses OFF, APPROVE_FIRST or AUTOMATIC.
OFF stops feedback at every destination, while eligible reviews can still record results.
PEOPLE or NOBODY under reviewedBy prevents automated review.
Review every practice left Off if the previous setting was intended to stop AI review.
Instance silent mode and workspace delivery pause control delivery, not review execution.

**People.**
Membership and reviewed developers use native accounts rather than SCM user IDs.
Roster sync can create an UNCLAIMED account before sign-in.

Sign-in claims the roster account instead of creating a second account.
AI choice and the preference for external comments and reminders apply across the account's workspaces.
Review conflicting imported preferences in the account settings.
A former member keeps membership history but has no active workspace access.

**Custom practice scripts.**
A script is a default export of definePracticeScript with meta and run(ctx).
run returns one candidate result for the supplied item. The script contract is version 3.

A null script uses the built-in script. A changed invalid script does not.
The migration transforms only exact audited historical scripts.
An unsupported script stops the migration, including a script retained in a catalog comparison base.
Rewriting a custom script before upgrade does not add its source to the audited map.
The refusal names the practice ID or catalog slug and identifies a comparison base when it applies.

Prepare these changes on the restored database with the previous release:

1. Save complete custom definitions and scripts outside the database, with their workspace and catalog identifiers.
2. For affected bundled instance entries, select **Restore Hephaestus default**.
3. For remaining custom instance entries, clear **Static analysis script** and save the definition.
4. If an affected catalog entry is absent, recreate it from the saved workspace definition. Use its original slug and no script.
5. Accept affected workspace catalog updates to advance their comparison bases to the prepared definitions.
6. For remaining custom workspace practices, clear **Static analysis script** and save the definition.
7. Test the migration again on a fresh restore with these changes.

A reset removes local customization. Save that content before the reset.
These changes create new active definitions. Do not edit pinned historical definitions or migration checksums.
Keep new review submission quiesced when you apply the tested changes to production.
Resume reviews for those practices only after you restore their custom content with tested version 3 scripts.

The old positional function and leads-only output do not run.
Remove meta.version, meta.kinds and meta.tokens from preview scripts. Use the core entry point and the kit for the item’s domain.
Do not return candidate-level severity.
Set severity on each problem, with CRITICAL, MAJOR, MINOR or TRIVIAL.
Use DOES_NOT_APPLY instead of NOT_APPLICABLE.

Use COULD_NOT_DETERMINE instead of UNDETERMINED.
Use evidence.openQuestions instead of evidence.undecidability.
Read item, review.origin, practice.versionId and about from the new context.
For a scan, about is null and the supplied item is a file at the pinned repository version.
A required model that is unavailable withholds the result. It does not produce a claim about missing evidence.
Use the practice-script guide's local test procedure before enabling the rewritten script.

Remaining queued work from the previous runtime is cancelled with CONTRACT_CHANGED.
The migration preserves its history and write receipts. It does not replay old output.
Start a fresh review when you need another result.

Pending source events remain and can start fresh reviews when their workspace policies permit them.
Before review execution resumes, record and disable automatic and requested review policies for workspaces that must stay paused.
Resume each workspace deliberately after the history checks and review-cost estimate.
Silent Mode and paused feedback delivery do not stop model calls.

Direct script model calls default to 200,000 tokens and 1,000 search units per attempt.
Set PRACTICE_REVIEW_DIRECT_MODEL_TOKENS_PER_ATTEMPT to change the token limit.
Set PRACTICE_REVIEW_DIRECT_MODEL_SEARCH_UNITS_PER_ATTEMPT to change the search-unit limit.
A spent limit returns HTTP 402. Calls already in flight can finish above the limit.
See [Practice review operations](https://docs.hephaestus.build/admin/practice-review-operations) for the minimum practice-review timeout.

**Custom API clients.**
Workspace paths below begin with /workspaces/{workspaceSlug}.
Use the generated OpenAPI contract from this release.

Changed practice-model bigint IDs and monetary amounts are decimal strings.
SCM mirror APIs keep their existing identifier types.
Nullable response fields are present with null rather than omitted.
There are no redirects from retired practice resource names.

| Earlier contract | New contract |
| --- | --- |
| /practices/reviews/observations | /practices/reviews/results |
| /practices/reviews/result-observations | /practices/reviews/problems |
| /practices/trace/{artifactKind}/{artifactId} | /practices/items/{itemId}/trace |
| /agents/jobs/{jobId} | /practices/reviews/{reviewId} |
| /practice-profile/review-runs | /practice-profile/reviews |
| /practices/backfill-runs | /practices/review-batches |
| artifactKind and artifactId | item reference and itemId |
| agentJobId | reviewId |
| aboutUserId and recipientUserId | developerId with a native account ID |
| practiceRevisionId | practiceVersionId |
| observations on a result | problems |
| evidenceRationale | explanation |
| evidence.undecidability | evidence.openQuestions |
| observedAt | recordedAt |
| feedback.channel | feedback.destination |
| IN_CONTEXT, IN_APP, IN_CHAT | ON_WORK, PRACTICE_PAGE, CONVERSATION |
| feedback.deliveryState | feedback.state |
| placements and dispatches | copies and sends |
| precomputeScript and practiceScript | definition.script |
| NOT_APPLICABLE and UNDETERMINED outcomes | DOES_NOT_APPLY and COULD_NOT_DETERMINE |
| INFO severity | TRIVIAL |
| HUMAN_APPROVAL autonomy | APPROVE_FIRST feedbackMode |
| invalidation and validity updates | result marks with create/remove operations |
| DISPUTED response resolution | DISAGREE |

The [operator result-text entry](https://github.com/hephaestus-build/Hephaestus/blob/main/MIGRATION.md) owns nullable summaries and withheld evidence.
Show the unavailable state while retaining safe outcome, practice, item and delivery facts.
Do not substitute an empty problem list for unavailable problem detail.
Feedback response resolution and usefulness remain independent dimensions.
Result outcomes and feedback response values are different enums.

**Rollback.**
Follow [failed-migration recovery](https://docs.hephaestus.build/admin/production-operations-runbook#if-migration-fails) to stop roles and restore the verified pre-upgrade backup.
Complete its [JetStream and provider-divergence procedure](https://docs.hephaestus.build/admin/production-operations-runbook#choose-the-broker-recovery-strategy-before-starting-consumers) before starting consumers.
Start the previous release with its matching images and configuration under mandatory pre-boot isolation.
Do not start old binaries against the migrated database.
The backup does not contain data recorded after the upgrade.

#### 🔴 Update custom pinned agent images

The application requires agent runtime contract 7 for incremental Problem drafts. Runtime 6 images do not provide the required toolkit export.

**Operators:** If you pin a custom agent image, rebuild it from the matching source and update its immutable reference with the application. Verify that its `hephaestus.agent.runtime-contract` label is `7`. Verified release locks already select a matching application and agent pair. The image contract check reports mismatches; it does not stop execution. Practice inputs and candidates remain on script contract 3.

### v0.93.0

#### 🔴 Audit text and native Hibernate JSON cutover

**Operators:** Schedule an outage for every replica of `server`, `worker`, and `webhook`.
Stop all runtime roles, external database writers, and scheduled database maintenance.
Verify restoration of a complete database backup before the migration.

The migration converts authentication details, both configuration snapshots, GitHub project options,
and connection audit detail from JSONB to TEXT.
It takes exclusive locks and rewrites tables, authentication partitions, and the configured pg_partman template.
Typed JSON data remains JSONB.
Unexpected source types or partition/template states stop the migration.

Apply the migration once while all writers remain stopped.
Confirm migration completion and future partition creation before restarting all runtime roles on the same new release.
Direct person-data exports of previously structured objects and arrays now return Strings that contain JSON text.
Check consumers that require nested export values.

Downgrade requires restoration of the verified backup from before this cutover.
Do not start the previous binary against the converted database.
Liquibase rollback stops because TEXT cannot recover the original JSONB representation.

Follow the complete [operator procedure](https://docs.hephaestus.build/admin/native-hibernate-json-cutover).

#### 🔴 Handle unavailable operator observation text

Observation summaries in the operator review API are optional when source access cannot be verified. This applies to observation lists and details, observations linked to feedback, and lineage records. The detail also withholds the rationale and evidence in that state.

**Operators:** Update external API consumers to handle an absent summary. The bundled interface displays the unavailable state and retains the observation’s outcome, practice, work and delivery facts. No manual database change is required.

#### 🔴 Upgrade usage verification with all writers stopped

**Operators:** Follow the [stopped-writer upgrade procedure](https://docs.hephaestus.build/admin/native-hibernate-json-cutover).
Do not mix runtime-role versions during this cutover.
An older repricer does not honor the new measurement-verification decision and can price usage that must remain unpriced.

The migration records historical verification as `UNKNOWN` without changing existing token counts, rates, or stored costs.
Historical `UNKNOWN` records that are already unpriced cannot be repaired automatically by adding rates.
Their existing current-month cap holds remain.
Previously priced amounts stay in totals; they do not gain a new verification claim.

New incomparable measurements are `UNVERIFIABLE` and have no confirmed cost, even when rates exist.
Adding a rate repairs only verified usage with positive billable tokens.
Do not assign historical verification through manual database edits.
This cutover does not recover provider usage that arrives after terminal accounting.

### v0.92.0

#### 🔴 Use the dedicated APIs instead of raw job output

Administrative job responses no longer return raw `output`. The nullable field remains in the schema, but its contents are withheld to keep private feedback outside administrative monitoring. Internal review results and delivery recovery are preserved.

**Operators:** Update custom clients that read job output to use the workspace's practice-review observation and feedback endpoints for the records they are permitted to read. Job responses still carry typed review outcomes, replacement references, phase timestamps and usage totals. The per-job usage endpoint carries recorded attempt usage and pricing state.

### v0.89.0

#### 🔴 Activity contributor kind

Custom clients of `GET /workspaces/{slug}/activity/people` and `GET /workspaces/{slug}/activity/people/{userId}` must read `kind` instead of `automation`.
`kind` is `PERSON`, `BOT` for a provider bot account, or `AUTOMATION` for an account that a workspace admin treats as automation.
A provider bot stays `BOT` even when an admin classified it.
The bundled webapp needs no operator action.

#### 🔴 Whole-workspace anonymous access is removed

Take and verify a database backup before this upgrade. Restore that backup if you must reverse the schema cutover.

The old public workspace flag no longer grants anonymous API access.
Workspace APIs now require sign-in and workspace access.
The replacement public activity page defaults to off for each workspace, including workspaces that used the old flag.

Set `HEPHAESTUS_PUBLIC_ACTIVITY_ENABLED=true` only when public activity pages are appropriate for this instance.
An instance administrator can override this default in the public activity settings API.
A workspace administrator must then enable each page explicitly.
Run a repository sync to confirm current provider visibility. Existing sync watermarks do not supply this confirmation.
Verify repaired activity history before publication and keep course workspaces private.
See the public activity administration guide for privacy controls, objections, and API paths.

### v0.88.0

#### 🔴 Activity API cutover

Custom clients must replace `GET /workspaces/{slug}/activity/summary` and `GET /workspaces/{slug}/activity/members` with `GET /workspaces/{slug}/activity/people`.
The response separates people and automation and counts each reviewed pull request once.
Use `range=30d`, `90d`, `1y`, or `all`, or provide `from` and `to` for a custom range.
Team filters use the `teams[].key` value. Repository filters use `repositories[].key` and can repeat `repo`.
All time serves full history without a range-width cap.
The list includes all contributors. Its weekly series carries contribution counts only; per-type and per-repository counts belong to the per-person endpoint.
The activity work endpoint also uses range presets and readable `team` and `repo` keys instead of `teamId`.
Use `GET /workspaces/{slug}/activity/people/{userId}` for a contributor's breakdown.
Use its `/work` subresource with `nextCursor` for subsequent pages.
The bundled webapp needs no operator action.
The database migration applies automatically and preserves the activity ledger.

### v0.86.0

#### 🔴 `DEPLOYMENT_ENVIRONMENT` replaces `SENTRY_ENVIRONMENT`

The name of a deployment, such as `production` or `staging`, is now a Hephaestus setting.
The webapp, the **Release** card, error reports and exported traces read it from `DEPLOYMENT_ENVIRONMENT`.
`SENTRY_ENVIRONMENT` is no longer read.
Before, every server reported its Sentry errors as `prod`.

Before you upgrade:

1. In `.env`, rename `SENTRY_ENVIRONMENT` to `DEPLOYMENT_ENVIRONMENT`.
   On a host with one environment file per stack, set `DEPLOYMENT_ENVIRONMENT` in each file.
   Without it, the instance reports `local`.
2. Use lowercase letters, digits, `.`, `_` or `-`, at most 64 characters.
   Hephaestus refuses to start with another value.
3. If you send errors to Sentry, change each alert rule and saved search that filters on `prod` to use the new name.

### v0.85.0

#### 🔴 Accept a new withholding reason in custom API clients

API responses can now include `PUBLIC_SUBJECT_INELIGIBLE` when saved public feedback does not meet the requirement to address the author’s work.

If a custom API client rejects unknown enum values, update it to accept `PUBLIC_SUBJECT_INELIGIBLE` before you upgrade.
The clients bundled with this release already accept this value.

#### 🔴 Practices across the workspace shows small counts

Practices across the workspace has no smallest count. Members of a workspace see every count of developers at each practice standing, also a count of one, and the typical range of a figure from one developer. The page names nobody, but in a small group a member can tell the standing of another developer. Before you upgrade an instance that serves people in the EU, tell your members in your privacy notice that other members see their standings in these counts. The TUM notice in `webapp/public/legal/profiles/tumaet/privacy.md` shows one wording. Record your data-protection decision for this audience, for example in your DPIA. The DPIA pre-screen in `docs/admin/dsms/dpia-prescreen.md` § 6 lists it as a reassessment trigger.

### v0.81.0

#### 🔴 Check events and pipeline events

The schema migration applies automatically. What needs a hand is the event stream: a GitHub App
created from an earlier manifest does not subscribe to `check_suite` and `status`, and a GitLab group
webhook registered by an earlier release does not send pipeline events. Add the two event
subscriptions under the GitHub App's Permissions & events, and grant Checks and Commit statuses read
if the app predates them (each installation approves the increase). Enable Pipeline events on each
GitLab group hook under the group's Settings → Webhooks, or delete the hook so it is registered again
on the next sync. Until then the head's check state arrives only with the scheduled sync, which reads
it with the pull request; nothing else is affected.

#### 🔴 Repository capture and agent image upgrade

Deploy matching server, worker and agent images: the agent image now carries runtime contract 3.
Drain running reviews before upgrading. Review and explicitly update stored source policies to
contract `1.2.0` using the source-policy upgrade instructions. Startup does not rewrite installed
policies; historical practice revisions and observations remain unchanged.

Remove `GIT_TREE_MAX_FILES`, `GIT_TREE_MAX_TOTAL_SIZE` and `GIT_TREE_MAX_FILE_SIZE`. A review now
captures the whole repository at the reviewed commit together with the Git history reachable from it,
so the retired 32 MiB tree bound is no longer a capacity estimate: provision worker storage for
repository mirrors, per-attempt snapshots and sandbox input archives. A repository whose checkout plus
mirrored history exceeds `GIT_MAX_SNAPSHOT_BYTES` (8 GiB by default) is refused whole rather than
captured in part; raise it for larger monorepos. `GIT_MAX_CONCURRENT_INGESTIONS` (default 2) caps how
many captured commits a worker writes to PostgreSQL at once.

Review the expanded repository-history scope with your deployment's privacy owner. Files deleted from
the current checkout can remain accessible in history.

Remove `SANDBOX_DOCKER_CLI`: sandbox inputs and results travel through the authenticated worker
gateway, not through the Docker CLI or a host-mounted context directory.

Remove `PRACTICE_REVIEW_EXECUTION_CAPTURE_ENABLED`; private execution capture is no longer
supported. A review retains its admitted observations and their citation verdicts, not its inputs,
model requests or session transcripts, and archived transcripts from earlier releases are neither
admission verdicts nor replay evidence.

Remove `HEPHAESTUS_FABRIC_GC_RETENTION_DAYS`; the content-addressed store and its retention sweep are
gone. Each server and worker container keeps its repository mirrors under its own
`HEPHAESTUS_FABRIC_ROOT` as `mirrors/<workspace-id>/<repository-id>.git`, beside its attempt folders;
the shipped Compose files give the worker its own volume for this. A mirror that is missing is cloned
again on the next sync or review, so the previous release's mirrors need no migration. After the
upgrade, once no attempt from the previous release is still running, delete the retired `sources/` and
`cas/` directories and the previous layout's per-job `jobs/<job-id>/` directories under that root. Do
not remove active attempt folders (`jobs/<workspace-id>/<job-id>/`) or `mirrors/`.

#### 🔴 Leaderboard, leagues and XP retired; Activity replaces them

The leaderboard, leagues and league points, experience points and levels, the league reset, and the
weekly Slack leaderboard digest are removed. So are the workspace switches **Leaderboard**, **XP and
level progression** and **Leagues**, the digest's day, time, channel and team settings, and the Slack
connection card's test message. Every workspace now has **Activity** and **Workspace activity**:
counts and lists of pull or merge requests, reviews, issues and comments, with members listed by
name — see [Activity](https://docs.hephaestus.build/user/activity).

**League, XP and leaderboard data is deleted by the upgrade.** The upgrade first attributes each recorded
merge to the pull request's author (a merge whose author is unknown is no longer counted for anyone) and
turns Heph off in workspaces that had it switched off, then drops these columns with their data:

- `workspace`: `mentor_enabled`, `leaderboard_enabled`, `progression_enabled`, `leagues_enabled`,
  `leaderboard_schedule_day`, `leaderboard_schedule_time`, `leaderboard_notification_enabled`,
  `leaderboard_league_cycle_at`
- `workspace_membership`: `league_points`
- `activity_event`: `xp`, with its check constraint and the leaderboard index

Nothing in the new release reads them, and neither the upgrade nor a rollback can bring the values back: a
rollback recreates the columns empty, with every switch off (Heph included) and every league point and XP
value at 0. **Before you upgrade,** stop the application, take the regular database backup
([Backup and restore](https://docs.hephaestus.build/admin/backup-restore)) — restoring it is the only way
back to the previous release with its data — and, if you want to keep the league and XP history outside
that backup, export it from the self-host directory:

```bash
(
set -eu
umask 077
cd /opt/hephaestus/docker/self-host
dc() { docker compose --env-file .env --env-file release-lock.env "$@"; }
snapshot_dir=$(mktemp -d "/var/tmp/hephaestus-leaderboard-snapshot-$(date -u +%Y%m%dT%H%M%SZ)-XXXXXX")
dc exec -T postgres psql -U root -d hephaestus -v ON_ERROR_STOP=1 -c "\copy (SELECT id, slug, mentor_enabled, leaderboard_enabled, progression_enabled, leagues_enabled, leaderboard_schedule_day, leaderboard_schedule_time, leaderboard_notification_enabled, leaderboard_league_cycle_at FROM workspace ORDER BY id) TO STDOUT WITH CSV HEADER" > "$snapshot_dir/workspace.csv"
dc exec -T postgres psql -U root -d hephaestus -v ON_ERROR_STOP=1 -c "\copy (SELECT m.workspace_id, m.user_id, u.login, m.league_points FROM workspace_membership m JOIN \"user\" u ON u.id = m.user_id ORDER BY m.workspace_id, m.user_id) TO STDOUT WITH CSV HEADER" > "$snapshot_dir/workspace_membership.csv"
dc exec -T postgres psql -U root -d hephaestus -v ON_ERROR_STOP=1 -c "\copy (SELECT id, workspace_id, actor_id, event_type, occurred_at, xp FROM activity_event WHERE xp <> 0 ORDER BY occurred_at, id) TO STDOUT WITH CSV HEADER" > "$snapshot_dir/activity_event_xp.csv"
(cd "$snapshot_dir" && sha256sum ./*.csv > SHA256SUMS)
printf 'Snapshot directory: %s\n' "$snapshot_dir"
)
```

The three files hold member logins and per-member scores, which is personal data: move the directory off
the host with your backups, encrypted, keep it only as long as you need the history, and then delete it. The
application never reads it back.

**Delete the removed settings.** Remove `LEADERBOARD_NOTIFICATION_ENABLED`, `LEADERBOARD_SCHEDULE_DAY`
and `LEADERBOARD_SCHEDULE_TIME` from your `.env`, and any `hephaestus.leaderboard.*` override. They are
no longer read.

**Activity is for the workspace only.** A publicly viewable workspace does not show activity to people
without a role in it. Old `/w/{workspaceSlug}/user/{username}` links open that member on Workspace
activity, and `/w/{workspaceSlug}/user/{username}/practice-groups/{groupSlug}` opens the group on the
Practice profile. Remove clients of the leaderboard, league, profile and Slack-digest endpoints; the
new read endpoints are under `/workspaces/{workspaceSlug}/activity/`.

#### 🔴 Classify unrecorded copies of "Confirm the outcome before closing the issue"

The upgrade withdraws this practice from automated review and switches off, at server startup, every
workspace copy whose persisted `source_curated_slug` names `issue-closed-with-unmet-outcome`. A copy with no
recorded source is left untouched and stays active: it was made and edited before Hephaestus recorded where
copies came from, and nothing stored distinguishes it from a practice a workspace wrote itself. A matching
slug is not proof of either, so the upgrade does not decide.

After the upgrade, list the candidates (read-only):

```sql
SELECT p.workspace_id, p.id, p.slug, p.name, p.autonomy
FROM practice p
WHERE p.slug = 'issue-closed-with-unmet-outcome'
  AND p.source_curated_slug IS NULL;
```

For each row, confirm with that workspace's administrator whether it is an old copy of the catalog practice
or the workspace's own practice. For an old copy, have the administrator set it to **Off** under
**Workspace administration → Practices → Review**, which records the change in the configuration audit
log; do not update the row directly. A practice the workspace wrote itself needs no action. Until a copy is
switched off it keeps being reviewed on issue close and can record results from the issue as it stands
after the close. Results recorded before the upgrade are unchanged.

#### 🔴 Database baseline upgrade path

Before starting v1.0.0, follow the [upgrade-path table](https://docs.hephaestus.build/admin/compatibility-policy#upgrade-paths-to-10).
It names the required intermediate release and when
[baseline synchronization](https://docs.hephaestus.build/admin/liquibase-baseline-runbook) is needed.
Use the signed v1.0.0 target image for synchronization.

Startup and baseline synchronization refuse migration history that has not reached the v0.77.4
cut-point. Do not mark missing migrations as applied. Stop all writers and test a full backup
restoration before synchronization. Fresh databases initialize automatically.

#### 🔴 Observation status, behavior assessment and outcome are distinct

Upgrade the server, sandbox runtime and webapp together. Drain running practice reviews before the
upgrade: old runtimes emit a combined outcome contract that the new server deliberately rejects.
Before resuming reviews, use the existing catalogue adoption flow to apply the updated bundled
practice definitions to installed workspace practices. Review instance-level overrides and custom
criteria for obsolete combined outcome labels and missing-capture instructions. Each observation must identify the specific behavior it assesses and explain that behavior’s desirability in context. Keep the behavior referent stable within the observation; different behaviors under one practice can receive different assessments. Adoption creates
new practice revisions; historical revisions are deliberately not rewritten, and workspace
customizations are not silently overwritten.

Custom API consumers and custom runtime integrations must use:

- `assessmentStatus`: `ASSESSED`, `NOT_APPLICABLE` or `UNDETERMINED`.
- `presence`: `PRESENT` or `ABSENT` only for assessed observations, otherwise null.
- `assessment`: contextual desirability of the specified behavior, `GOOD` or `BAD` only for assessed observations, otherwise null.
- `outcome`: read-only `POSITIVE` for PRESENT/GOOD or ABSENT/BAD, `NEGATIVE` for PRESENT/BAD or ABSENT/GOOD, null when unassessed. Never annotate it independently.
- `severity`: required exactly for negative outcomes, null otherwise.

Use outcome—not assessment alone—for severity, feedback eligibility, counts and trends. Developer summaries expose `positiveCount` and `negativeCount`; standing observations expose their descriptive `kind` separately from outcome.

Observation filters now have an independent assessment-status facet. Review observation counts use
`undetermined`, not `inconclusive`. Raw historical review outputs remain historical artifacts; they
are not rewritten to pretend that old runtimes emitted the new contract.

Back up and verify restoration before upgrading. Liquibase maps existing PRESENT/ABSENT rows to
ASSESSED, NOT_APPLICABLE rows to NOT_APPLICABLE and INCONCLUSIVE rows to UNDETERMINED. It clears
presence for the two unassessed statuses and clears non-judgmental legacy severity values on non-BAD
rows under the old assessment-as-verdict convention. It swaps GOOD/BAD on historical ABSENT rows to preserve their original outcome; it does not reinterpret their evidence against new criteria. Historical negative severity values and all evidence are retained. A historical BAD row without severity halts
the migration: inspect its recorded evidence and repair through an audited operator procedure rather
than assigning a fabricated default. Do not bypass this precondition.

Downgrading in place is unsupported because the runtime and wire contracts also changed. Recover by
restoring the verified pre-upgrade backup and the matching application/runtime versions together.

#### 🔴 Review and update stored source policies before resuming reviews

Pause new practice reviews and let in-flight reviews finish before upgrading. This runtime uses source
contract `1.2.0`; it does not evaluate new reviews under `1.0.0` or `1.1.0`. A complete, verified empty diff now
qualifies as captured evidence, while each practice still establishes its own occasion and observation.

Review custom practices and instance catalogue overrides through their normal administration endpoints.
Read the stored definition and replace `automatedReviewPolicy.sourceContractVersion` with `1.2.0` in an
explicit policy update, preserving the remaining policy fields, bindings and criteria unless the review
calls for a deliberate change. Merely updating criteria preserves the old policy and is not sufficient.
Use the catalogue adoption flow for updated bundled definitions and for reviewed instance overrides in
workspaces. Confirm the effective definition reports `1.2.0` before resuming reviews.

Stored definitions remain readable and editable. Historical review evidence and its original contract
and catalogue digest remain unchanged; historical readiness reports are not re-derived under the new
policy. Do not edit stored evidence or rewrite released migrations to change their version.

#### 🔴 Upgrade contextual practice assessment and delivery together

Pause new practice reviews and let in-flight reviews and feedback dispatches finish before upgrading.
Deploy the matching server and review runtime versions together; deploy the matching webapp for the
updated assessment explanations. Resume reviews after the updated components are healthy.

Use the existing catalogue adoption flow to apply the updated bundled definitions to workspace
practices. Review instance overrides and customized criteria as well: each observation identifies a
specific behavior, records whether it occurred, and assesses whether that behavior is desirable or
undesirable in its evidenced context. Keep the behavior referent stable within the observation.
Different behaviors under one practice can have different assessments. Outcomes remain derived from
presence and assessment; unassessed statuses remain outside the outcome matrix. Catalogue adoption
creates new revisions and does not rewrite historical judgments or silently replace customizations.

Remove `PRACTICE_REVIEW_PROGRESS_FOOTER` and any
`hephaestus.practice-review.progress-footer` override. Automatic cross-review progress footers and
inferred resolved/regressed history summaries are no longer produced. Recorded observations,
delivered feedback and prepared feedback remain available. Matching a location or omitting a prior
observation does not establish that a concern was resolved.

Reactions and delivery receipts apply to their exact bound observations. They do not suppress a new
observation merely because its practice and file match an earlier one. Custom inline-delivery
integrations must preserve the supplied `deliveryKey` unchanged as an opaque receipt-correlation key;
newly composed placements use the observation occurrence identity rather than location grouping.

#### 🔴 Research participation uses the consent API only

Custom API clients must stop reading or writing `participateInResearch` on `/user/settings`.
That endpoint now manages practice-feedback delivery only. Read the current decision through
`GET /user/consent` and record a research choice through `PUT /user/consent/research`, using the
current wording version and research organisation returned by the consent API. Use the generated
OpenAPI contract for the complete request. Do not copy a historical preference flag into a new
consent decision: the person must answer the wording and organisation shown to them.

The shipped webapp already uses this consent flow. Slack App Home links to User settings instead of
maintaining a separate research toggle. Historical database records are retained; no destructive
migration or SMTP activation is required for this change.

#### 🔴 Practice definitions and observation outcomes use a new contract

This is a coordinated pre-1.0 cutover, not a rolling upgrade. The old practice occasion list and
observation assessment axes are removed. Do not run old workers or API clients against the new server.

1. Stop review scheduling and let reviews finish, or cancel them. Stop every runtime role.
2. Create a database backup and verify that you can restore it into a separate database.
3. Only if observations serve an actual research or audit purpose, export the original observation
   fields while every role is still stopped. The upgrade removes them, they cannot be rebuilt from the
   new outcome, and a rotating database backup is for recovering this instance, not an archive:

   ```sql
   \copy (SELECT id, workspace_id, practice_id, practice_revision_id, agent_job_id, assessment_status,
          presence, assessment, severity, observed_at FROM observation) TO 'observation-axes.csv' CSV HEADER
   ```

   Record beside it the Hephaestus release and the last applied Liquibase changeset it was taken from.
   Keep the export encrypted in private custody with access limited to that purpose, and retain and
   erase it under the retention and erasure rules your organization already applies to that purpose,
   not your backup rotation. Do not publish it. This step sets no new policy and leaves research
   archives you have already published unchanged.
4. Convert custom catalogue files and API payloads to the flat fields `signals`, `evidenceRequirements`, `reviewWhen`,
   `subject`, and optional `precondition`. The precondition's explanation is `skipReason`.
   `reviewWhen` is an object of descriptor-owned state selections, not a shared draft flag.
   For non-draft pull or merge requests use `{"draftStatus":["NOT_DRAFT"]}`; use `{}` for no state
   restriction. Issues have no draft state, documents expose active or archived, and conversations
   expose no lifecycle selection. The migration preserves the previous pull-request draft restriction
   without adding it to other work types.
   Describe the positive standard in `criteria`; remove matrix-based instructions.
5. Update integrations to submit `outcome`: `MET`, `NOT_MET`, `NOT_APPLICABLE`, or `UNDETERMINED`.
   Supply severity exactly for `NOT_MET`. Retain the appropriate evidence warrants.
6. Deploy matching server, review runtime, webapp, and browser extension versions and let the forward
   migration finish. Before restarting reviews, update the criteria of persisted practices to describe
   the positive standard, without matrix instructions: instance customizations first, then workspace
   copies through catalogue updates, then custom practices in the editor. Then verify practice editing,
   one review, its recorded result, and delivery.

The migration preserves historical criteria and does not invent missing historical definitions.
Unsupported or ambiguous existing definitions must not be silently flattened: the migration refuses an
occasion without a known subject or a true or false draft choice, and any stored definition field the
new contract does not read. If the migration rejects existing data, keep the instance stopped and
inspect the reported prerequisite failure.
Do not disable its checks or change a released migration.

**Recovery:** Stop all roles and restore the verified pre-upgrade database backup together with the
previous application images. The removed fields cannot be reconstructed reliably from new results;
there is no automatic reverse conversion. Old positive labels are not evidence that the complete new
practice standard was met.

#### 🔴 GitHub App: request user authorization during installation

Connecting a GitHub App installation to a workspace now needs the App's own client credentials, and
the App must request user authorization during installation. Before you upgrade, open the App's
settings on GitHub and do the following:

1. Under **Identifying and authorizing users**, turn on **Request user authorization (OAuth) during
   installation**. Set the **Callback URL** to `https://<APP_HOSTNAME>/oauth/callback/github`.
2. Under **General**, copy the **Client ID** and generate a **client secret**. Set them as
   `GH_APP_CLIENT_ID` and `GH_APP_CLIENT_SECRET` in `.env`.

Workspaces that are already connected keep working. The upgrade disconnects any GitHub App
connection that never recorded its installation, because such a connection could not run. It also
keeps each installation in the first workspace that connected it. Connections the upgrade
disconnects are recorded in their connection history. See
[GitHub integration](https://docs.hephaestus.build/admin/github-integration#connecting-an-installation-to-a-workspace).

#### 🔴 Set `WEBHOOK_ROUTING_SECRET` for GitLab group webhooks

GitLab group webhooks now carry a token that names the one workspace they deliver for, signed with a
new secret. The application server and the webhook receiver refuse to start without it.

1. Generate a value of at least 32 printable characters, for example with `openssl rand -hex 32`. It
   must differ from `WEBHOOK_SECRET` and from both encryption keys.
2. Set it as `WEBHOOK_ROUTING_SECRET` for the application server and the webhook receiver; the
   reference Compose files forward it to both. The self-host `setup.sh` generates it when it is empty.
3. Deploy. Each GitLab workspace registers its new group webhook on its next sync, or at once with
   **Sync now**. The group webhook earlier versions registered keeps working; delete it on GitLab once
   the new one appears, so each event is not received twice.

To rotate the secret later, move the current value to `WEBHOOK_ROUTING_PREVIOUS_SECRET`, set a new
`WEBHOOK_ROUTING_SECRET`, deploy, let every GitLab workspace sync once, then clear
`WEBHOOK_ROUTING_PREVIOUS_SECRET` and deploy again.

#### 🔴 Heph follows AI models; the Chat with Heph switch is removed

The workspace switch **Chat with Heph** is removed. Heph is offered to every member of a workspace
where a Heph model is ready under **Administration → AI models**, in the web app and in Slack direct
messages, and each member's AI choice still applies.

**Before upgrading**, turn off **Chat with Heph** under **Administration → Settings** in every
workspace that should not offer Heph. The upgrade carries the switch over: in each workspace where it
is off, it turns off the Heph model rows, and a workspace admin turns them back on under **AI models**.
Which rows were on before is not recorded, so undoing this needs a pre-upgrade backup.

The [restore-clone lockdown](https://docs.hephaestus.build/admin/backup-restore) now turns off every
Heph model row instead of the removed switch; turn them back on under **AI models** after lifting the
lockdown.

#### 🔴 Heph follows the workspace setting, not per-account grants

Hephaestus no longer reads `mentor_access` rows in `account_feature`. Every member of a workspace
with **Chat with Heph** turned on can use Heph, in the web app and in Slack direct messages, subject
to their own AI choice. If you granted `mentor_access` to only some accounts to run a limited pilot,
turn off **Chat with Heph** under the workspace's **Administration → Settings** before upgrading, in
every workspace you are not ready to open to all of its members. Leftover `mentor_access` rows have
no effect and need no clean-up.

#### 🔴 Rename hephaestus.mentor.max-frame-chars to hephaestus.mentor.max-frame-bytes

Earlier releases counted the limit on one message from a Heph sandbox in characters. It now counts
UTF-8 bytes, and the setting is named for that. If you set `hephaestus.mentor.max-frame-chars`, set the
same value as `hephaestus.mentor.max-frame-bytes` before upgrading. The old name is ignored, and a value
left under it falls back to the default of 1 MiB, which is also the largest value allowed. The same
value admits the same plain-ASCII messages. Text with multi-byte characters uses more of it.

#### 🔴 Connect a worker before serving Heph conversations

Heph no longer starts a sandbox directly on the application server. Before upgrading, configure at least one worker with `HEPHAESTUS_HUB_URL` pointing to the server's `/api/workers/connect` endpoint and `HEPHAESTUS_WORKER_REGISTRATION_TOKEN` matching the server registration configuration. Give the worker spare mentor capacity. You must run a connected worker for Heph. Upgrade the server and workers together; mixed versions are not supported. The server retains chat admission, context and thread persistence; the worker owns the sandbox and LLM proxy.

Without a connected worker, new conversations report “Heph is busy” and can be retried after capacity becomes available. Saved thread history remains in PostgreSQL. Existing live sessions end during the upgrade and are restored on the next turn. The application server can disable its worker role with `hephaestus.runtime.worker.enabled=false`; changes to the reference Compose socket mounts are separate.

#### 🔴 Sandbox cleanup keeps mentor sandbox networks it cannot attribute

Automatic cleanup now removes a mentor conversation's sandbox network, storage and containers only
after the application container that started them has stopped or restarted. It keeps anything whose
owner it cannot establish, so two kinds of leftover now need removing by hand:

- Sandbox networks created before this upgrade record no owner and are never removed automatically.
- An application that cannot identify its own container, because it runs outside Docker or has a
  customised `hostname:`, records no owner either. If a crash leaves one of its conversation networks
  behind, that conversation cannot start a new sandbox until you remove it; the error names the
  network. Keep the default container hostname to avoid this.

A network with no recorded owner can be removed safely only while every application process of this
installation that uses the Docker daemon is stopped. Plan that pause, then follow
[Removing leftover sandbox networks](https://docs.hephaestus.build/admin/configuration-readiness#removing-leftover-sandbox-networks).
Practice review sandboxes need no action.

#### 🔴 Upgrade practice reviews to the frozen workspace folder

Pause new practice reviews and let in-flight reviews finish before upgrading. Deploy the matching
server and review runtime together. The runtime reads the flat version-3 task and `INDEX.json` from
the job folder; the old capped capture and artifact-source manifest are removed.

Apply updated bundled practice definitions through catalogue adoption. Review customized practices
and set their automated-review policy's `sourceContractVersion` to `1.3.0` through practice authoring.
This creates a new practice revision; do not rewrite historical revisions or approvals. A policy
pinned to an older source contract does not authorize the expanded folder and is refused before
model execution. Submit a new review under the updated practice revision rather than reusing an
old attempt. Resume reviews after the matching components and updated practices are ready.

The wider workspace scope remains subject to existing visibility, member choices, processor routing,
consent, withdrawal, tenancy, retention and erasure checks. Maintainer engineering approval is not
controller or DPO approval. Operators must include Slack-thread and person-scoped history in their
applicable privacy-notice review before deployment; the pending TUM review remains a separate release
obligation.

#### 🔴 Restrict the new management listener and update custom probes

The default management port is now **9090**, separate from the application port. Outside the supported Compose stacks, management binds to loopback (`127.0.0.1`) by default. For a remote scraper, explicitly set `MANAGEMENT_SERVER_ADDRESS` to a trusted private interface and restrict ingress with firewall policy. `GET /actuator/prometheus` needs no user token on that listener; it is refused on the application port.

The supported Compose stacks already keep 9090 private: they expose it to `shared-network`, do not publish it to the host, and do not route it through the proxy. They bind management to the container’s own `shared-network` address through its network-qualified hostname, not to its sandbox interfaces. Their container probes and proxy readiness checks use the application-port paths and are updated automatically.

**Operators with other deployment configurations:** before starting the new version, restrict management ingress to trusted private services with network and firewall policy. Do not publish the management port to the internet. Update custom management probes to port 9090, or set `MANAGEMENT_PORT` to a port distinct from both the application and sandbox gateway ports. Application-port liveness and readiness probes now use `/livez` and `/readyz`; update custom proxy checks that used `/actuator/health/liveness` or `/actuator/health/readiness` on the application port. See [Scrape metrics](https://docs.hephaestus.build/admin/observability#scrape-metrics) for scrape configuration and alert rules.

#### 🔴 Impersonation replaced by read-only user views

Remove clients of `POST /auth/impersonate` and `POST /auth/impersonate:exit` and the `X-Impersonation-Allow-Writes` header. Drop `hephaestus.auth.impersonation-max-lifetime`. Replace `HEPHAESTUS_AUTH_RATE_LIMIT_IMPERSONATE_CAPACITY` / `_PERIOD` with `HEPHAESTUS_AUTH_RATE_LIMIT_USER_VIEW_CAPACITY` / `_PERIOD` where you override the defaults. Administrators who were inside an impersonation session sign in again.

#### 🔴 Repository coverage no longer stops conversation or document reviews

Selecting repositories, including leaving that selection empty, limits repository-backed practice reviews only. Slack conversations and Outline documents follow the workspace's people selection and their existing collection permissions and AI choices.

If you used selected repositories to stop all practice reviews, turn **Start practice reviews** off before upgrading. Alternatively, an empty selected people list covers nobody across every kind of work. Existing repository and branch selections remain unchanged; this upgrade does not add repositories or activate channels or documents.

#### 🔴 Check your research obligations before upgrading

The research question in setup and in User settings now asks for consent to an area of research, not to one research project.
The area is how developers work and learn, and how AI systems can review and support that work, including building and running benchmarks and evaluation datasets for such AI systems.
The wording version changed, so every account answers setup once more.
An earlier "yes" does not carry over: `participatesInResearch` reports false, and research survey invitations stop, until the account answers.

If `HEPHAESTUS_RESEARCH_ORGANIZATION` is unset, setup is the terms alone and nothing about research changes for you.

If you set it, do these steps before you upgrade:

1. Read "What operators must do" in the [Legal Pages guide](https://docs.hephaestus.build/admin/legal-pages#the-optional-research-question).
2. Meet each obligation, or unset the variable.
3. Update your privacy notice with the retention, recipients and withdrawal limits of your research. The TUM notice shows the expected shape.
4. Choose a research organization name that reads correctly in the sentence "If you say yes, the organization may use your data for research."

Nothing is dropped from the database. Earlier decisions stay in the ledger as history.

#### 🔴 Run the worker container on a single host

The application server now runs without the worker role and without the Docker socket; AI sandboxes run only in `application-worker`, which the single-host install now starts. Before upgrading, run `./setup.sh` in `docker/self-host`: it adds `HEPHAESTUS_WORKER_REGISTRATION_TOKEN` to `.env` and does not change other values. Without that token `docker compose` refuses to start the stack. The worker joins the host's Docker group through `DOCKER_GROUP_ID`; the application server no longer needs it.

The worker adds a container with a 3 GB memory limit (`APPLICATION_WORKER_MEM_LIMIT`); check the host against the install guide's sizing, and lower `APPLICATION_SERVER_MEM_LIMIT`, `APPLICATION_WORKER_MEM_LIMIT` or `WEBHOOK_SERVER_MEM_LIMIT` before starting if it needs smaller limits. `SANDBOX_MAX_CONCURRENT` and the five-minute drain on shutdown now apply to the worker.

`SANDBOX_DOCKER_APP_SERVER_CONTAINER_ID` is removed. It let any named container join every sandbox network; now only the worker that starts a sandbox joins its network, as the container Docker identifies by its default hostname. Remove the variable from `.env`. A worker configured with `hephaestus.sandbox.docker.app-server-container-id` directly refuses to start. A worker running outside Docker joins no sandbox network, so practice reviews without internet access are refused there.

#### 🔴 Administrator attribution uses account references

The upgrade removes the historical display-login attribution from silent-mode and instance model settings. The settings, change times and other operational facts are retained. The old values are not matched to names, logins or email addresses. New changes use a stable account reference, which is detached when that account is erased.

Existing `ADMIN` and `USER` connection audit rows lose their untyped `actor_ref` and free-text `detail`. Event types, state changes and times remain. New connection history uses typed account references. Provider and system event references are unchanged.

Before upgrading, take and verify a backup if your retention policy requires the old attribution. This removal cannot be reversed from the upgraded database; recovery requires the verified pre-upgrade backup. Do not backfill attribution by matching display logins to accounts.

The migration also clears membership-history subject references that have no exact contributor ID.
It retains the recorded role changes and their times. No historical name, login or email is used to
recover attribution.

Pending integration authorizations must be started again after the upgrade. Older signed OAuth states are rejected rather than interpreting their historical display-login attribution as an account reference.

New pending integration authorizations are tied to the initiating account and removed by person erasure. Existing nonce rows remain without account attribution; no old identity is inferred.

Heph's hidden conversation memory resets once on upgrade. Old runtime journals have no complete
exact-person provenance, so the migration clears them without matching text, names, logins or emails.
Every visible message, conversation title and time stays. Recovery of the cleared hidden journals
requires a verified pre-upgrade backup. Users may need to repeat earlier context to Heph.

Existing source connections are registered for exact person requests, including Slack and Outline
sources with no account login. This creates only provider reference data and carries native processing
controls across equivalent origins. It does not change visible content or infer identity ownership.
If an Outline connection has mirrored documents but no exact provider instance, the upgrade stops and
names that connection ID. Restore a verified state with its exact source binding before upgrading;
do not recover a binding from names, logins, email addresses or document content.

#### 🔴 Webhook loss alerts move to backlog

`webhook.stream.unacknowledged.deletions` and `webhook.stream.unacknowledged.gap` are removed: on a
stream shared by several organisations they reported caught-up consumers as losing messages. An alert
on either now never fires. Replace it with one on `webhook.stream.consumer.pending` or
`webhook.stream.consumer.ack.pending` staying above zero or growing for several monitor intervals, and
keep the one on `webhook.stream.poll.age`. Neither gauge counts lost webhooks; the
[webhook ingestion operations](https://docs.hephaestus.build/admin/webhook-ingestion-operations) page
says what they do mean.

### v0.80.0

#### 🔴 Name your research organisation, and check your legal pages, before upgrading

First-login setup is now one short screen with no operator-specific text. It states the terms of use
and points at `/imprint` and `/privacy` for who runs the deployment, what it stores and for how long.
Configure both before you upgrade — on an instance still serving the built-in placeholder, the first
thing a new account reads now points at nothing.

**The research question is no longer asked by default.** It appears only where
`HEPHAESTUS_RESEARCH_ORGANIZATION` names the organisation that runs the study, which the screen and
the account-settings switch then show beside the choice; consent has to identify its controller. Set
it if you run a study. Leave it unset and setup is the terms alone, the settings switch is hidden, and
`PUT /user/consent/research` answers 404 — accounts that already answered keep their recorded
decision, and nothing is deleted.

The wording changed and its version moved to `2026-09-11`, so every account accepts it once more. The
gate runs on requests from existing sessions too, so signed-in users meet it as soon as the deployment
finishes rather than at their next sign-in. A browser tab left open on the old setup screen during the
upgrade shows an error and needs a full refresh, not the page's own retry.

Every research decision now records the organisation it was asked about, so changing
`HEPHAESTUS_RESEARCH_ORGANIZATION` later asks each account again rather than carrying an answer over to
a different name. Terms acceptance is untouched by that change.

Nothing is dropped from the database this release. `consent_notice` and its archived `2026-08-30`
wording stay exactly as the baseline seeded them; `consent_decision.notice_sha256` only loses its
`NOT NULL`, and `research_organization` is added alongside it. Decisions recorded from here on identify
their wording by `notice_version`, which points at the release that published it. A replica still
running the previous image keeps working against this schema, and rolling the image back stays
possible. A later release removes the archive and the digest.

#### 🔴 Drain sandboxes before upgrading their installation ownership

Before upgrading, drain active practice reviews and interactive conversations, then stop the
installation's workers. Assign a stable `SANDBOX_DOCKER_OWNER` to all worker-capable roles sharing
one database. Use different values for installations sharing a Docker daemon but not a database.
The default is `default`; valid values contain 1–63 lowercase letters, digits and hyphens and start
with a letter or digit.

After verifying which installation owns them, remove its remaining legacy containers and networks.
The new cleanup does not adopt containers that only carry `hephaestus.managed=true`, legacy
`agent-net-` networks, or resources labelled with another owner. Do not remove another installation's
resources. Restart the upgraded roles with the same owner value and confirm a new practice review
can complete. Apply the same drain procedure before changing the owner later.

### v0.79.0

#### 🔴 Achievements retired

Achievement pages, unlock notifications, the skill-tree designer, and achievement administration are no longer available. Remove bookmarks and external links to these routes:

- `/w/{workspaceSlug}/achievements`
- `/w/{workspaceSlug}/user/{username}/achievements`
- `/w/{workspaceSlug}/admin/achievements`
- `/w/{workspaceSlug}/admin/achievement-designer`

Remove clients of `/workspaces/{workspaceSlug}/users/{login}/achievements` and its `/definitions`, `/recalculate`, and `/reload` endpoints. Workspace responses no longer include `achievementsEnabled`; stop sending that property to the workspace feature-update endpoint. There are no replacement achievement endpoints or redirects.

The upgrade permanently drops `user_achievement` and `workspace.achievements_enabled`. There is no data export, retained achievement storage, or replacement feature in the application. Activity history, practice feedback, leaderboards, leagues, and XP progression remain available.

Before upgrading, back up the database and stop every application runtime role (`server`, `worker`, and `webhook`). Start only the upgraded version after migration; a rolling deployment with older versions is not supported for this removal. Returning to an older version requires restoring the pre-upgrade database backup; Liquibase cannot recover deleted progress.

### v0.78.0

#### 🔴 PostgreSQL 18 and baseline synchronization required

PostgreSQL 18 is the only supported database major version. The bundled image no longer accepts a PostgreSQL 17 build target.

If your database is still on PostgreSQL 17, first complete the [v0.77.4 PostgreSQL 17-to-18 upgrade procedure](https://github.com/hephaestus-build/Hephaestus/blob/v0.77.4/docs/admin/backup-restore.mdx#postgresql-17-to-18). Verify a successful restore into PostgreSQL 18 and keep an off-host backup before removing the old database. Then install this release. Do not attach a PostgreSQL 17 data directory to the PostgreSQL 18 image.

All existing databases, including PostgreSQL 18 installations, must complete the [baseline synchronization runbook](https://docs.hephaestus.build/admin/liquibase-baseline-runbook) before the candidate application starts. Take and test-restore a full backup, verify the v0.77.4 cut-point, stop writers, and run `changeLogSyncToTag baseline_v0_77_4` using the candidate image. Unsynchronized existing schemas fail startup. Fresh databases apply the baseline automatically.

### v0.77.0

#### 🔴 Product feedback and survey answers of already-deleted accounts are removed during the upgrade

**Affected**: every deployment where someone deleted their account after sending product feedback or
answering a survey.

**Before**: account deletion left those submissions in the database. A deleted account is kept as an
empty placeholder rather than removed, so the cascade that would have deleted its submissions never
fired, and the free text and answers stayed indefinitely.

**After**: account deletion removes them, and the database migration in this release removes the ones
earlier releases left behind. Both deletions are permanent.

**Migration**: nothing to configure. If you have to keep those submissions — a legal hold, or
reporting on product feedback — export them or take a database backup before you deploy this release.

#### 🔴 Rename Docker sandbox settings

Update custom application YAML, Spring property overrides, environment files and Compose overrides
before upgrading. Removed names are not aliases: a worker-role process refuses to start while any of
them is still set, naming the one it found and the replacement to use.
The shipped single-host deployment still uses the local Docker socket and gateway port `8081`.

| Old Spring property | Replacement |
| --- | --- |
| `hephaestus.sandbox.docker-host` | `hephaestus.sandbox.docker.host` |
| `hephaestus.sandbox.tls-verify` | `hephaestus.sandbox.docker.tls-verify` |
| `hephaestus.sandbox.cert-path` | `hephaestus.sandbox.docker.cert-path` |
| `hephaestus.sandbox.container-runtime` | `hephaestus.sandbox.docker.container-runtime` |
| `hephaestus.sandbox.app-server-container-id` | `hephaestus.sandbox.docker.app-server-container-id` |
| `hephaestus.mentor.docker-cli` | `hephaestus.sandbox.docker.cli` |

- Rename `SANDBOX_TLS_VERIFY` to `SANDBOX_DOCKER_TLS_VERIFY` and `SANDBOX_CONTAINER_RUNTIME` to
  `SANDBOX_DOCKER_CONTAINER_RUNTIME`. Update any direct `HEPHAESTUS_*` environment overrides to match
  the new Spring property paths as well.
- `SANDBOX_DOCKER_HOST` is unchanged. New explicit environment mappings are
  `SANDBOX_DOCKER_CERT_PATH`, `SANDBOX_DOCKER_APP_SERVER_CONTAINER_ID` and `SANDBOX_DOCKER_CLI`.
- For TCP Docker access, mount client certificates read-only into each worker-capable container,
  enable TLS verification, and set `SANDBOX_DOCKER_CERT_PATH` to the mounted directory containing
  `ca.pem`, `cert.pem` and `key.pem`. Java operations and interactive commands now share these
  settings. Inherited `DOCKER_CONTEXT`, `DOCKER_HOST`, and Docker TLS variables no longer select
  the interactive daemon.

For defaults and connection requirements, see the
[Docker configuration reference](https://docs.hephaestus.build/admin/configuration-readiness#docker-configuration).

Restart `application-server` and `application-worker`, confirm their Docker health check, and run a
practice review and an interactive mentor session. If gVisor is configured, inspect the created
sandbox's runtime to confirm it is `runsc`; a successful application boot does not validate the
daemon's runtime registry.

### v0.76.0

#### 🔴 LLM usage accounting older than the retention window is deleted after upgrade

**Affected**: every deployment that has recorded LLM usage for longer than the window — 400 days
unless you set your own — and any operator whose commercial or tax retention obligations cover that
accounting data.

**Before**: rows in the LLM usage ledger — per-run token counts and cost, attributed to a workspace —
were kept indefinitely.

**After**: a daily sweep deletes usage older than the configured window, default 400 days. The
deletion is irreversible. Each pass deletes in batches for at most five minutes, so the first sweep
after upgrade begins clearing the historical backlog and later sweeps finish it; a pass that stops
with rows still expired reports the `incomplete` privacy-job outcome.

**Migration**: if your accounting obligations require a longer window, set
`HEPHAESTUS_LLM_USAGE_RETENTION` (an ISO-8601 duration, for example `P3650D`) before deploying this
release. No action is needed to keep the default.

#### 🔴 Allow sandbox traffic on the new gateway port

Worker sandboxes now connect to `SANDBOX_API_PORT` (default `8081`) instead of the worker application port. Allow sandbox-to-worker traffic on this port and keep the worker application and management ports private. Set `SANDBOX_API_PORT` consistently in the worker and any network policy that restricts sandbox egress. A single-container install runs the worker role in the `application-server` container, so it opens this port too.

`SANDBOX_LLM_PROXY_PORT` is no longer read — set `SANDBOX_API_PORT` instead.

### v0.75.0

#### 🔴 Integration credentials use a dedicated encryption key

**Affected**: reference deployments and custom deployments. The supported self-host installer handles
this migration automatically.

**Before**: integration credentials use `HEPHAESTUS_SECURITY_ENCRYPTION_KEY`.

**After**: integration credentials use `HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY`, which can be
rotated independently without invalidating sessions or unrelated encrypted data.

**Migration**: before deploying, set `HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY` to the current
value of `HEPHAESTUS_SECURITY_ENCRYPTION_KEY`. After every runtime is upgraded, follow the
[credential-key rotation procedure](https://ls1intum.github.io/Hephaestus/admin/credential-key-rotation)
to replace it with an independent key. Self-hosted installations using `docker/self-host/setup.sh`
receive the initial value automatically.

#### 🔴 Server logs are now structured JSON

**Affected**: deployments that parse the application server or worker console output — log shippers,
alerting rules, or grep-based tooling built for the previous plain-text lines.

**Before**: the server and worker wrote human-readable plain-text log lines.

**After**: every log line is a single JSON object (`timestamp`, `level`, `logger`, `message`, `mdc`,
`stacktrace`), in every profile, with known credential formats masked before output. Nothing changes
for `docker compose logs` itself — the lines are still printed to the console, just as JSON.

**Migration**: point existing parsers at the JSON fields instead of the plain-text layout. Filters
that matched free text can key on `mdc."job.id"` and `mdc."workspace.id"`; see the log-correlation
examples in the practice-review operations guide.

#### 🔴 Restore PostgreSQL 17 data into PostgreSQL 18 before starting the stack

**Affected**: operators upgrading a self-hosted deployment whose database volume was initialized by
the bundled PostgreSQL 17 image.

**Migration**: before starting this release, follow the
[Backup & Restore](https://ls1intum.github.io/Hephaestus/admin/backup-restore#postgresql-17-to-18).
Keep the PostgreSQL 17 volume until the upgraded deployment passes its acceptance checks.

#### 🔴 Upgrade the server and agent image together

**Affected**: every deployment that runs practice reviews or mentor sessions.

**Before**: runtime contract v1 executes staged TypeScript with Bun.

**After**: runtime contract v2 executes it with Node.js 24, a 256 MB V8 heap ceiling, and scoped
runner filesystem permissions. The server reports an image with a different contract as unsupported before sandbox work
starts.

**Migration**: deploy the application server/worker and the matching `hephaestus-agent` image from the
same release. Do not reuse or independently pin an older agent image. After deployment, verify the agent
image reports `hephaestus.agent.runtime-contract=2`, contains Node 24, and does not contain Bun.

#### 🔴 Practice reviews now require the person being evaluated to be a workspace member

**Affected**: every workspace with practice reviews turned on, and in particular any workspace whose
monitored repositories take pull requests from outside the organization.

**Before**: a review ran on any pull request or issue in a monitored repository, whoever it evaluated.

**After**: coverage has two dimensions — repositories and people — and both must admit the work. The
people dimension is workspace membership. Most practices evaluate the pull-request or issue author;
reviewer practices evaluate the reviewer. If that person is not an identifiable linked human member, no
review runs and no feedback is prepared about them.

**Migration**: nothing to change before upgrading.

**Operator action after upgrading**: open **Practices → Review → When and where** and confirm the
**People** and **Repositories** counts. Existing members need no action. If expected contributors are
missing, follow [Who counts as a person](https://ls1intum.github.io/Hephaestus/admin/practice-review#who-counts-as-a-person).

#### 🔴 Production configuration is validated before startup

**Affected**: deployments that activate the `prod` Spring profile and have a missing, malformed, or
role-inconsistent required setting.

Production processes now validate the applicable requirements in the configuration readiness
catalog together and refuse to start until every reported error is resolved. The failure report
identifies properties and documentation but never includes configured values.

**Action**: before upgrading, compare every production role's settings with the
[configuration readiness guide](https://ls1intum.github.io/Hephaestus/admin/configuration-readiness).
Run a staging process with the production profile and correct every required setting it reports.
After the server role starts, an instance administrator can inspect the redacted facts through
`GET /api/admin/configuration-readiness`.

#### 🔴 Practice area API names are replaced by practice group names

**Affected**: anything calling the application API directly. The generated Hephaestus web client is
updated in this release.

The product concept previously exposed as a *practice area* is now consistently named a **practice
group**. All `/practice-areas` routes move to `/practice-groups`; request and response fields such as
`areaSlug` and `areaName` become `groupSlug` and `groupName`; catalog collections named `areas` become `groups`. Generated schema
names likewise use `PracticeGroup` instead of `PracticeArea`. The developer-facing practice projection
moves from `/practices/learner` to `/practices/reviewed` and is named `ReviewedPractice`. The old routes
and field names are removed rather than aliased. This rename does not change which practices belong together.

**Action**: regenerate API clients and replace direct uses of the retired routes, schema names, and
fields. No operator configuration changes are required; the database migration runs automatically.

#### 🔴 The feedback reaction endpoint is replaced by a response endpoint

**Affected**: anything calling the application API directly. The Hephaestus web app is unaffected —
it never used these endpoints outside its generated client, which ships regenerated in this release.

Removed:

```
POST /workspaces/{workspaceSlug}/practices/feedback/{feedbackId}/reactions   (submitReaction)
GET  /workspaces/{workspaceSlug}/practices/feedback/{feedbackId}/reactions   (getLatestReaction)
```

**Before**: a developer submitted an `action` (`ADDRESSED`, `DISPUTED`, or `NOT_APPLICABLE`) and an
optional `explanation`. Submissions were append-only and the GET returned the latest reaction.

**After**: the response endpoint renames `action` to `resolution` and `explanation` to `comment`. It
also accepts an independent, optional `usefulness` answer:

```
PUT    /workspaces/{workspaceSlug}/practices/feedback/{feedbackId}/response
GET    /workspaces/{workspaceSlug}/practices/feedback/{feedbackId}/response
DELETE /workspaces/{workspaceSlug}/practices/feedback/{feedbackId}/response
GET    /workspaces/{workspaceSlug}/practices/feedback/resolution-counts
```

PUT replaces the complete response with `usefulness` (`HELPFUL` / `UNHELPFUL`), `resolution`
(`ADDRESSED` / `DISPUTED` / `NOT_APPLICABLE`), or both, plus an optional `comment` that is required
when disputing. Omitted fields are cleared. Repeating the same PUT has no effect. DELETE removes the
complete response and is safe to repeat. GET returns the response that currently stands.

**Action**: repoint any direct API caller at the new endpoint. Existing response history is preserved and
participates in current-response reads; no operator data migration is required.

#### 🟢 Pull request previews are opt-in, and the preview stack no longer runs agents

**Affected**: anyone operating the Coolify preview application. Upgrading needs no action — the
steps below are only for turning previews on. Staging and production are untouched.

**Before**: Coolify deployed a preview for every same-repository pull request by itself, onto a stack
that ran the agent sandbox and mounted the Docker socket. The v0.74.0 note below told you to name the
preview's agent image in its `.env`.

**After**: a preview starts only when someone with push access adds the `preview` label to a pull
request — including a layer stacked on another branch — and at most `PREVIEW_MAX_ACTIVE` run at once
(three unless you set that repository variable). GitHub Actions drives the whole lifecycle
through a signed webhook; Coolify's own automatic deployment stays off. The stack runs no agent, no
worker, no webhook ingestion and no integrations, so `AGENT_ENABLED` and
`HEPHAESTUS_AGENT_IMAGE_REFERENCE` are gone from `docker/preview/.env.example` and the v0.74.0 note
below no longer applies to previews.

**Do this before enabling previews:**

1. Refresh the preview application's Compose definition from `main`, then confirm the cached
   definition has no Docker socket and no staging network.
2. Turn Coolify's automatic deployment and its repository webhook off, and preview deployments on.
3. Create a repository label named exactly `preview`.
4. Set the repository variables and the two scoped secrets listed in the
   [preview runbook](https://github.com/ls1intum/Hephaestus/blob/main/docker/preview/README.md),
   then delete the old broad `COOLIFY_API_TOKEN`.

Leaving previews disabled needs no action: without the repository variables, nothing deploys.

#### 🔴 The PostHog integration is removed

**Affected**: deployments that carry PostHog settings in their `.env` or pass them as deploy
secrets. Deployments that never configured PostHog need no changes.

**Before**: the stack read `POSTHOG_ENABLED`, `POSTHOG_API_HOST`, `POSTHOG_PROJECT_ID`,
`POSTHOG_PROJECT_API_KEY`, and `POSTHOG_PERSONAL_API_KEY`, and the webapp could load the PostHog
client and its cloud-backed surveys when they were set.

**After**: none of these variables are read anywhere; product feedback and surveys are stored in
the instance's own PostgreSQL and reviewed in **Administration → Feedback**. No replacement
variable exists.

**Migration**: delete the `POSTHOG_*` lines from your `.env` and remove any corresponding deploy
or preview secrets; leftover values are ignored but keep an unused credential in circulation, so
also revoke the PostHog personal API key in PostHog itself if one was ever issued. The schema
migration for the new feedback tables runs automatically.

#### 🔴 The runtime envelope is hardened: NATS requires credentials, remote databases require TLS

**Affected**: every reference and self-hosted deployment. Deployments using a remote (non-Compose)
PostgreSQL host are additionally affected by the TLS requirement.

**Before**: the bundled NATS broker accepted unauthenticated connections (mitigated only by its
loopback bind), a remote `DATABASE_URL` with `sslmode=disable` connected silently, and each server
role opened up to 30 database connections.

**After**: the broker, publisher, and consumer all require the same credentials and the stack
refuses to start without them; in production a remote PostgreSQL host without `sslmode=require`
(or stronger) aborts startup; each server role's connection pool defaults to 20.

**Migration**: before deploying, set `NATS_USERNAME` and `NATS_PASSWORD` in your `.env` to freshly
generated random values (do not reuse an application key); deployments driven by the deploy
workflow need the same pair as environment secrets. If your database host is remote, add
`sslmode=require`, `verify-ca`, or `verify-full` to `DATABASE_URL` — only set
`HEPHAESTUS_DATABASE_ALLOW_INSECURE_REMOTE=true` after explicitly accepting plaintext transport.
Optional tuning: `HIKARI_MAXIMUM_POOL_SIZE` restores a larger pool, and the new
`APPLICATION_SERVER_CPUS`/`APPLICATION_SERVER_PIDS_LIMIT` (plus the worker and webhook variants)
adjust the container ceilings.

#### 🔴 Container images moved to `ghcr.io/hephaestus-build/<image>`

**Affected**: deployments with registry mirrors, egress allowlists, or hand-written image
references. The standard install and upgrade flow — Compose plus the signed release lock — follows
the move on its own.

**Before**: all images lived under `ghcr.io/ls1intum/hephaestus/<image>`, and every release lock was
signed as `ls1intum/Hephaestus`.

**After**: this release and everything newer publish under `ghcr.io/hephaestus-build/<image>`
(without the redundant `hephaestus/` segment) and sign as `hephaestus-build/Hephaestus`. Releases
published before the move are unchanged: GHCR packages do not transfer between organizations, so
their images stay at `ghcr.io/ls1intum/hephaestus/<image>`, and their signatures name the old
repository forever. `security/release-identities.json` records which namespace and signing identity
each release uses, and the upgrade gate, deploy workflows, and lock verifier resolve it per release.

**Migration**: allow `ghcr.io/hephaestus-build/<image>` wherever registry access is restricted or
mirrored. If you overrode an image reference by hand — for example a pinned
`HEPHAESTUS_AGENT_IMAGE_REFERENCE` — repoint it at the new namespace when you next update the pin.
When verifying an old release yourself, use the identity recorded for its version in
`security/release-identities.json` (releases before the move:
`https://github.com/ls1intum/Hephaestus/.github/workflows/release.yml@refs/heads/main`).

#### 🔴 The PostgreSQL upgrade keeps the stable volume name — rely on the verified dump, not a retained volume

**Affected**: operators performing the PostgreSQL 17 → 18 migration this release requires. This
entry corrects the "Restore PostgreSQL 17 data into PostgreSQL 18" entry above, which predates it.

**Before**: this release was drafted to start PostgreSQL 18 on a new `postgresql-data-v18` volume,
leaving the PostgreSQL 17 volume in place as the rollback path.

**After**: the Compose volume keeps its stable `postgresql-data` name. The documented upgrade
verifies the dump, removes the PostgreSQL 17 volume, and lets PostgreSQL 18 initialize a fresh
cluster under the same name. Starting the new release without migrating is safe: a PostgreSQL 18
container attached to PostgreSQL 17 data refuses to start rather than coming up healthy and empty.

**Migration**: follow the current
[Backup & Restore](https://docs.hephaestus.build/admin/backup-restore#postgresql-17-to-18)
procedure. Where the entry above says to keep the PostgreSQL 17 volume until acceptance checks
pass, keep the verified dump (with an off-host copy) instead — the old volume is removed during
the upgrade, so the dump is the rollback artifact.

#### 🔴 The PostHog integration is removed

**Affected**: deployments that carry PostHog settings in their `.env` or pass them as deploy
secrets. Deployments that never configured PostHog need no changes.

**Before**: the stack read `POSTHOG_ENABLED`, `POSTHOG_API_HOST`, `POSTHOG_PROJECT_ID`,
`POSTHOG_PROJECT_API_KEY`, and `POSTHOG_PERSONAL_API_KEY`, and the webapp could load the PostHog
client and its cloud-backed surveys when they were set.

**After**: none of these variables are read anywhere; product feedback and surveys are stored in
the instance's own PostgreSQL and reviewed in **Administration → Feedback**. No replacement
variable exists.

**Migration**: delete the `POSTHOG_*` lines from your `.env` and remove any corresponding deploy
or preview secrets; leftover values are ignored but keep an unused credential in circulation, so
also revoke the PostHog personal API key in PostHog itself if one was ever issued. The schema
migration for the new feedback tables runs automatically.

#### 🔴 The runtime envelope is hardened: NATS requires credentials, remote databases require TLS

**Affected**: every reference and self-hosted deployment. Deployments using a remote (non-Compose)
PostgreSQL host are additionally affected by the TLS requirement.

**Before**: the bundled NATS broker accepted unauthenticated connections (mitigated only by its
loopback bind), a remote `DATABASE_URL` with `sslmode=disable` connected silently, and each server
role opened up to 30 database connections.

**After**: the broker, publisher, and consumer all require the same credentials and the stack
refuses to start without them; in production a remote PostgreSQL host without `sslmode=require`
(or stronger) aborts startup; each server role's connection pool defaults to 20.

**Migration**: before deploying, set `NATS_USERNAME` and `NATS_PASSWORD` in your `.env` to freshly
generated random values (do not reuse an application key); deployments driven by the deploy
workflow need the same pair as environment secrets. If your database host is remote, add
`sslmode=require`, `verify-ca`, or `verify-full` to `DATABASE_URL` — only set
`HEPHAESTUS_DATABASE_ALLOW_INSECURE_REMOTE=true` after explicitly accepting plaintext transport.
Optional tuning: `HIKARI_MAXIMUM_POOL_SIZE` restores a larger pool, and the new
`APPLICATION_SERVER_CPUS`/`APPLICATION_SERVER_PIDS_LIMIT` (plus the worker and webhook variants)
adjust the container ceilings.

#### 🔴 Container images moved to `ghcr.io/hephaestus-build/<image>`

**Affected**: deployments with registry mirrors, egress allowlists, or hand-written image
references. The standard install and upgrade flow — Compose plus the signed release lock — follows
the move on its own.

**Before**: all images lived under `ghcr.io/ls1intum/hephaestus/<image>`, and every release lock was
signed as `ls1intum/Hephaestus`.

**After**: this release and everything newer publish under `ghcr.io/hephaestus-build/<image>`
(without the redundant `hephaestus/` segment) and sign as `hephaestus-build/Hephaestus`. Releases
published before the move are unchanged: GHCR packages do not transfer between organizations, so
their images stay at `ghcr.io/ls1intum/hephaestus/<image>`, and their signatures name the old
repository forever. `security/release-identities.json` records which namespace and signing identity
each release uses, and the upgrade gate, deploy workflows, and lock verifier resolve it per release.

**Migration**: allow `ghcr.io/hephaestus-build/<image>` wherever registry access is restricted or
mirrored. If you overrode an image reference by hand — for example a pinned
`HEPHAESTUS_AGENT_IMAGE_REFERENCE` — repoint it at the new namespace when you next update the pin.
When verifying an old release yourself, use the identity recorded for its version in
`security/release-identities.json` (releases before the move:
`https://github.com/ls1intum/Hephaestus/.github/workflows/release.yml@refs/heads/main`).

#### 🔴 The PostgreSQL upgrade keeps the stable volume name — rely on the verified dump, not a retained volume

**Affected**: operators performing the PostgreSQL 17 → 18 migration this release requires. This
entry corrects the "Restore PostgreSQL 17 data into PostgreSQL 18" entry above, which predates it.

**Before**: this release was drafted to start PostgreSQL 18 on a new `postgresql-data-v18` volume,
leaving the PostgreSQL 17 volume in place as the rollback path.

**After**: the Compose volume keeps its stable `postgresql-data` name. The documented upgrade
verifies the dump, removes the PostgreSQL 17 volume, and lets PostgreSQL 18 initialize a fresh
cluster under the same name. Starting the new release without migrating is safe: a PostgreSQL 18
container attached to PostgreSQL 17 data refuses to start rather than coming up healthy and empty.

**Migration**: follow the current
[Backup & Restore](https://docs.hephaestus.build/admin/backup-restore#postgresql-17-to-18)
procedure. Where the entry above says to keep the PostgreSQL 17 volume until acceptance checks
pass, keep the verified dump (with an off-host copy) instead — the old volume is removed during
the upgrade, so the dump is the rollback artifact.

#### 🔴 The PostHog integration is removed

**Affected**: deployments that carry PostHog settings in their `.env` or pass them as deploy
secrets. Deployments that never configured PostHog need no changes.

**Before**: the stack read `POSTHOG_ENABLED`, `POSTHOG_API_HOST`, `POSTHOG_PROJECT_ID`,
`POSTHOG_PROJECT_API_KEY`, and `POSTHOG_PERSONAL_API_KEY`, and the webapp could load the PostHog
client and its cloud-backed surveys when they were set.

**After**: none of these variables are read anywhere; product feedback and surveys are stored in
the instance's own PostgreSQL and reviewed in **Administration → Feedback**. No replacement
variable exists.

**Migration**: delete the `POSTHOG_*` lines from your `.env` and remove any corresponding deploy
or preview secrets; leftover values are ignored but keep an unused credential in circulation, so
also revoke the PostHog personal API key in PostHog itself if one was ever issued. The schema
migration for the new feedback tables runs automatically.

#### 🔴 The runtime envelope is hardened: NATS requires credentials, remote databases require TLS

**Affected**: every reference and self-hosted deployment. Deployments using a remote (non-Compose)
PostgreSQL host are additionally affected by the TLS requirement.

**Before**: the bundled NATS broker accepted unauthenticated connections (mitigated only by its
loopback bind), a remote `DATABASE_URL` with `sslmode=disable` connected silently, and each server
role opened up to 30 database connections.

**After**: the broker, publisher, and consumer all require the same credentials and the stack
refuses to start without them; in production a remote PostgreSQL host without `sslmode=require`
(or stronger) aborts startup; each server role's connection pool defaults to 20.

**Migration**: before deploying, set `NATS_USERNAME` and `NATS_PASSWORD` in your `.env` to freshly
generated random values (do not reuse an application key); deployments driven by the deploy
workflow need the same pair as environment secrets. If your database host is remote, add
`sslmode=require`, `verify-ca`, or `verify-full` to `DATABASE_URL` — only set
`HEPHAESTUS_DATABASE_ALLOW_INSECURE_REMOTE=true` after explicitly accepting plaintext transport.
Optional tuning: `HIKARI_MAXIMUM_POOL_SIZE` restores a larger pool, and the new
`APPLICATION_SERVER_CPUS`/`APPLICATION_SERVER_PIDS_LIMIT` (plus the worker and webhook variants)
adjust the container ceilings.

#### 🔴 Container images moved to `ghcr.io/hephaestus-build/<image>`

**Affected**: deployments with registry mirrors, egress allowlists, or hand-written image
references. The standard install and upgrade flow — Compose plus the signed release lock — follows
the move on its own.

**Before**: all images lived under `ghcr.io/ls1intum/hephaestus/<image>`, and every release lock was
signed as `ls1intum/Hephaestus`.

**After**: this release and everything newer publish under `ghcr.io/hephaestus-build/<image>`
(without the redundant `hephaestus/` segment) and sign as `hephaestus-build/Hephaestus`. Releases
published before the move are unchanged: GHCR packages do not transfer between organizations, so
their images stay at `ghcr.io/ls1intum/hephaestus/<image>`, and their signatures name the old
repository forever. `security/release-identities.json` records which namespace and signing identity
each release uses, and the upgrade gate, deploy workflows, and lock verifier resolve it per release.

**Migration**: allow `ghcr.io/hephaestus-build/<image>` wherever registry access is restricted or
mirrored. If you overrode an image reference by hand — for example a pinned
`HEPHAESTUS_AGENT_IMAGE_REFERENCE` — repoint it at the new namespace when you next update the pin.
When verifying an old release yourself, use the identity recorded for its version in
`security/release-identities.json` (releases before the move:
`https://github.com/ls1intum/Hephaestus/.github/workflows/release.yml@refs/heads/main`).

#### 🔴 The practice-area endpoints are replaced by practice-group endpoints

**Affected**: anything calling the application API directly — scripts, dashboards, or an integration
built against `/practice-areas` or the developer practice list. Deployments that only run the
bundled web client need no changes; it ships updated in this release.

**Before**: practice groupings were served under `/workspaces/{workspaceSlug}/practice-areas`, with
`PracticeArea` schemas and `areaSlug` parameters. A developer's practices came from
`/workspaces/{workspaceSlug}/practices/learner`, and a reaction to delivered feedback was recorded
through its own endpoint.

**After**: the same groupings are served under `/workspaces/{workspaceSlug}/practice-groups`, with
`PracticeGroup` schemas and `groupSlug` parameters — _practice area_, `PracticeArea`, `areaSlug` and
`/practice-areas` are retired names, not synonyms, and no alias remains. The developer practice list
is `/workspaces/{workspaceSlug}/practices/reviewed`. A developer's response to delivered feedback —
whether it was helpful, how it was handled, and an optional explanation — is written with `PUT
/workspaces/{workspaceSlug}/practices/feedback/{feedbackId}/response`. Map helpfulness to
`usefulness`, handling to `resolution`, and the optional explanation to `comment`. This combined
response endpoint replaces the earlier reaction endpoint. Every one of these answers only for the
signed-in developer.

**Migration**: update each caller's paths, parameter names and response field names to the group
spelling, move any caller of `/practices/learner` to `/practices/reviewed`, and switch reaction
writes to the combined response endpoint. Response history recorded before the upgrade is preserved
and readable through the new endpoint, so nothing needs re-entering. Regenerate any client
built from `server/openapi.yaml`.

#### 🔴 The PostgreSQL upgrade keeps the stable volume name — rely on the verified dump, not a retained volume

**Affected**: operators performing the PostgreSQL 17 → 18 migration this release requires. This
entry corrects the "Restore PostgreSQL 17 data into PostgreSQL 18" entry above, which predates it.

**Before**: this release was drafted to start PostgreSQL 18 on a new `postgresql-data-v18` volume,
leaving the PostgreSQL 17 volume in place as the rollback path.

**After**: the Compose volume keeps its stable `postgresql-data` name. The documented upgrade
verifies the dump, removes the PostgreSQL 17 volume, and lets PostgreSQL 18 initialize a fresh
cluster under the same name. Starting the new release without migrating is safe: a PostgreSQL 18
container attached to PostgreSQL 17 data refuses to start rather than coming up healthy and empty.

**Migration**: follow the current
[Backup & Restore](https://docs.hephaestus.build/admin/backup-restore#postgresql-17-to-18)
procedure. Where the entry above says to keep the PostgreSQL 17 volume until acceptance checks
pass, keep the verified dump (with an off-host copy) instead — the old volume is removed during
the upgrade, so the dump is the rollback artifact.

### v0.74.0

#### 🔴 An agent image reference naming a channel tag is now refused

**Affected**, and either one is enough:

- `HEPHAESTUS_AGENT_IMAGE_REFERENCE` set to a channel tag — `:latest`, `:stable`, `:edge`, `:main`,
  or a partial version such as `:0.73`, which we retag onto every patch release in that line — or to
  a reference with no tag at all.
- **`IMAGE_TAG=latest`** — which earlier versions of `docker/.env.example` shipped as the default —
  or **`IMAGE_TAG=0.73`**. The reference now derives from `IMAGE_TAG`, so such a deployment resolves
  `agent-pi:latest` or `agent-pi:0.73` without ever naming it, and the refusal applies just the same.

Check both with `grep -E 'IMAGE_TAG|HEPHAESTUS_AGENT_IMAGE_REFERENCE'` over your deployment
configuration before you upgrade. `AGENT_ENABLED=false` does **not** exempt you: the check runs at
startup, whether or not the sandbox is ever used. A release deploy that leaves both alone takes the
signed digest pin and is unaffected.

The boot fails with one of:

```
hephaestus.agent.image.reference must not be a channel tag
hephaestus.agent.image.reference names a version series rather than one release
```

**Before**: the agent sandbox image fell back to `ghcr.io/ls1intum/hephaestus/agent-pi:latest` when
nothing else supplied a reference. `latest` tracks the newest **release**, so a deployment tracking
`main` ran its application server against an agent image built from a different commit. Nothing
reported it: practice reviews and mentor sessions simply failed inside the container.

**After**: the reference follows your deployment's own `IMAGE_TAG`, so the sandbox image is the one
built from the same commit as the application server. A channel tag is refused at startup with a
message naming the fix, because it can only ever name a pairing no release produced.

**Action**: set `IMAGE_TAG` to a full release version (`0.74.0`) or to a full commit SHA — never
`latest`, and never the `0.74` series. Then, if you also set the reference override, remove it or
replace it with a digest:

```bash
# either: remove the HEPHAESTUS_AGENT_IMAGE_REFERENCE line entirely (recommended) — do not
# leave it present and empty, which binds an empty reference and fails the boot for a second reason
#
# or: pin the exact image you mean
HEPHAESTUS_AGENT_IMAGE_REFERENCE=ghcr.io/ls1intum/hephaestus/agent-pi@sha256:<digest>
```

A deployment tracking `main` keeps `HEPHAESTUS_RELEASE_PIN_SKIP=true` and
`HEPHAESTUS_AGENT_IMAGE_REQUIRE_DIGEST=false`; the derived reference is a matched tag, not a digest.
See [Release image lock](https://ls1intum.github.io/Hephaestus/admin/release-image-lock).

#### 🟡 Preview deployments name their own agent image

**Affected**: preview stacks (`docker/preview/`) that run practice reviews or the mentor from a pull
request which does not touch `docker/agents/**`.

A preview now derives its agent image from its own commit, and CI publishes one at that commit only
when the pull request changed the agent tree or a workflow. Previously such a preview silently used
the last release's image. Set `HEPHAESTUS_AGENT_IMAGE_REFERENCE` in the preview's `.env` to the agent
image you want it to exercise — `docker/preview/.env.example` shows the line. (Superseded for
previews in v0.75.0: previews no longer run agents, and that variable is gone from the file.)
#### 🔴 `NATS_JS_MAX_FILE` is gone, and webhook streams now have a disk bound

**Affected**: every deployment.

**Do this before upgrading:**

1. **If you set `NATS_JS_MAX_FILE`, replace it with `NATS_JS_MAX_FILE_BYTES`, in bytes.** The old
   variable is no longer read by anything. Nothing warns you: a deployment that had `100G` silently
   drops to the new 16 GiB default. `50G` becomes `NATS_JS_MAX_FILE_BYTES=53687091200`.
2. **Check your current stream sizes** with `nats stream report`. A stream already larger than its
   new ceiling is left exactly as it is and logs an error on every start until you decide — see
   below.
3. **Keep the per-stream ceilings totalling under `NATS_JS_MAX_FILE_BYTES`**, or the receiver refuses
   to start.

Everything else in this entry is context.

---

Webhook streams were bounded only by message count, which says nothing about disk: on one deployment
2,000,000 GitHub deliveries came to 32.3 GB, filled the host, stopped the broker writing, and every
inbound webhook was dropped until the broker was restarted by hand.

**Two bounds now, not three.** `HEPHAESTUS_WEBHOOK_STREAM_MAX_AGE` stays at 180 days: it is the
*ceiling*, the longest a delivery is kept if disk allows. `HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES`
(1 GiB per stream) and `HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES_GITHUB` (10 GiB) are the *floor* under
it, and on a busy deployment they are what actually decides retention. Both are true; which one binds
is a function of your volume, and the server now publishes the answer as
`webhook.stream.oldest.message.age{stream}`, in seconds, so you can read your own effective retention
rather than infer it. The message-count bound is gone: a count describes neither disk nor time.

The byte ceiling is sized against what a shed message costs, not against the age ceiling. The nightly
reconciliation sync re-fetches the last `MONITORING_TIMEFRAME` days from the provider API, so a
webhook shed inside that window is recoverable by other means and one shed outside it is recoverable
by nothing. If you raise `MONITORING_TIMEFRAME`, raise the byte ceilings with it.

`NATS_JS_MAX_FILE_BYTES` sets the broker's own budget and the application's from one value, and the
server refuses to start if the per-stream bounds sum above it:

```
Webhook stream bounds total 21474836480 bytes, over the 17179869184-byte broker storage budget
```

Set it below the free space on the broker's volume. This is the difference between the broker filling
its own budget — where it refuses new messages and recovers by itself — and filling the filesystem,
where it cannot write its own metadata and stays wedged even after space is freed.

**A stream that already exceeds its new bound is left exactly as it is.** Bounding it deletes the
excess the moment it applies, so startup withholds the change and logs what it would cost:

```
Stream github limit change withheld because it would delete stored messages:
[maxBytes -1 -> 10737418240 (32300000000 bytes stored, 21562581760 would be deleted)] —
set hephaestus.webhook.stream.allow-destructive-limit-updates=true to apply it
Stream github bound removal withheld because it would leave the stream unbounded:
[maxMessages 2000000 -> -1 (no byte bound is in force to replace it)] —
get hephaestus.webhook.stream.max-bytes[-by-stream] applied first
```

Decide the bound first — raise `HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES_GITHUB` if the size the log
reports is legitimate for your traffic, keeping the total under `NATS_JS_MAX_FILE_BYTES`. Then set
`HEPHAESTUS_WEBHOOK_STREAM_ALLOW_DESTRUCTIVE_LIMIT_UPDATES=true` for one start-up to apply it, and
unset it again. Until you do, the stream keeps the message-count cap it already has: the count cap is
only released once a byte ceiling is in force to replace it, so an upgrade cannot leave a stream with
no limit at all.

Streams that already fit inside the bound are bounded automatically, with nothing deleted. Subjects,
retention mode, storage and discard policy are never rewritten by a deployment. A stream whose shape
has drifted from what this deployment expects is left entirely alone and logged — repair it with
`nats stream edit` before the bound can be applied.

Full metric roster and the wedged-broker procedure:
[Webhook ingestion operations](https://ls1intum.github.io/Hephaestus/admin/webhook-ingestion-operations).


#### 🟡 Readiness now answers for more than "the process started"

**Affected**: every deployment that monitors `/actuator/health/readiness`, and in particular any that
runs webhook receiving in the same container as the application — a single-container install, or the
preview stacks.

`/actuator/health/readiness` was the stock probe group on every container: the process's own
availability state and nothing else. The group meant to add the message-consumer, practice-review and
webhook checks was written under a property path Spring does not bind, so it was silently ignored, and
a container whose broker had stopped accepting writes answered 200 throughout. Those checks are now in
the probe. Expect readiness to follow broker availability, and on a combined-role container expect a
broker outage to take it out of load-balancer rotation until the broker returns.

**Migration**: none, but re-read what you alert on. If your dashboards treated readiness as a liveness
signal, it now reports operational dependencies as well, which is the point.

#### 🟡 Message-queue consumers now expire after 30 days with nothing connected

**Affected**: deployments that may be offline for more than 30 consecutive days and must resume
exactly where they left off. Everything else needs no action.

`HEPHAESTUS_INTEGRATION_CONSUMER_INACTIVE_THRESHOLD` previously defaulted to *never expire*. Nothing
else removes a consumer, so any stack that shared a broker and was deleted rather than shut down — a
preview, a test environment — left its consumers and their undrained backlogs on that broker
permanently, one generation per deleted stack.

**What it measures is connection, not traffic.** A running deployment holds standing requests against
its consumers, so it resets the clock continuously even while its queues are completely silent. Only a
deployment that no longer exists ages out, which is why 30 days is safe: no restart, deploy or incident
reaches it.

**Migration**: nothing to do, unless your deployment can be down for more than 30 days and must not
skip what arrived meanwhile. In that case set `HEPHAESTUS_INTEGRATION_CONSUMER_INACTIVE_THRESHOLD=0s`
before upgrading, which switches expiry off exactly as before. A consumer that does expire is recreated
pointing at new messages only, so it skips anything that arrived while the deployment was gone.

`0s` preserves the consumer's *position*, not the *messages*. Those expire on the stream's own
retention, independently, so a deployment offline past the stream's byte or age ceiling comes back to
a cursor pointing at messages the stream no longer holds — the loss counter will say so on the first
poll. Switching expiry off buys you the stream's retention window, not an unbounded one.

Values between `0s` and `1h` are now rejected at startup — a threshold that short expires a consumer
across an ordinary restart, which is the data loss the setting exists to avoid:

```
inactive-threshold (PT30M) must be 0 to disable reaping, or at least PT1H
```

#### 🟡 Reviewer-side practices keep the old wording until you update them

**Affected**: workspaces created before this release that use the shipped practices
*leaves useful, specific review comments*, *asks rather than demands*, and *reviews substantively*.

Those three judge how somebody **reviews** a teammate's change. Until this release an occasion did not
record whose conduct it judged, so their observations were filed against the author of the change
rather than the reviewer who wrote the comments. An occasion now records it, and a review that cannot
name the reviewer does not run.

A workspace installs the shipped catalog once, so an existing workspace still holds the old wording.
Open **Practice catalog** and apply the update to those three practices to pick it up. Until you do,
they behave exactly as they did before — nothing new is recorded against the wrong person, because the
new guard reads the occasion and the old wording still says *author*.

#### 🔴 `GIT_STORAGE_PATH` is now `HEPHAESTUS_FABRIC_ROOT`

**Affected**: deployments that set `GIT_STORAGE_PATH` to anything other than `/data/git-repos`. Check
with `grep GIT_STORAGE_PATH` over your deployment configuration before you upgrade. Deployments that
use the shipped Compose files unchanged are **not** affected: those files already pinned this path to
`/data/git-repos` and now pass the new name for the same directory, mounted from the same volume.

**Before**: `GIT_STORAGE_PATH` (`hephaestus.git.storage-path`) named the directory holding repository
working copies, and the rest of the on-disk cache — content-addressed evidence blobs and per-job
manifests — fell back to it whenever `HEPHAESTUS_FABRIC_ROOT` was unset.

**After**: `HEPHAESTUS_FABRIC_ROOT` is the only name for that directory. `GIT_STORAGE_PATH` is read
nowhere and has no alias.

**Nothing warns you.** Everything under this root is a rebuildable cache, so an instance that keeps
only the old variable starts, passes its health check and reviews normally — it simply writes to
`/data/git-repos` instead of the path you chose. If that path is not a mounted volume on your
deployment, it is the container's own writable layer: it grows with every clone, is discarded on
every restart, and presents as repeated full re-clones and a container disk filling up. The tree at
your old path is left where it is, no longer read and no longer swept.

**Migration**: before starting the new version, set `HEPHAESTUS_FABRIC_ROOT` to the value
`GIT_STORAGE_PATH` had, then remove `GIT_STORAGE_PATH`. The directory layout beneath the root is
unchanged, so the existing contents are picked up as they are and nothing has to be re-fetched.

Set it where the container will actually read it. The shipped Compose files pin
`HEPHAESTUS_FABRIC_ROOT: /data/git-repos` literally on the server and worker services, so a value in
`docker/.env` is ignored — edit those `environment:` blocks, or set it wherever your own orchestration
passes environment to those two roles.

#### 🔴 The evidence-cache retention window must be at least one day

**Affected**: deployments that set `HEPHAESTUS_FABRIC_GC_RETENTION_DAYS`
(`hephaestus.fabric.gc-retention-days`). The shipped default is `30` and is valid; if you have not
set this, there is nothing to do.

This is the number of days a review's cached evidence and job manifest are kept before the daily
sweep removes them. `0` was previously accepted, and did the opposite of what it looks like: instead
of switching the sweep off it made every cached job directory eligible for deletion on the next run.
A value below `1` is now rejected.

**Migration**: if you set it to `0` or a negative number, set a real window before upgrading.
Otherwise the server role does not start and reports:

```
hephaestus.fabric.gc-retention-days must be positive
```

No value switches the sweep off; set a long window instead.

#### 🔴 Practice-review API uses one vocabulary

**Affected**: API clients that configure practices, read findings (now observations), or manage AI bindings.

Update clients in the same deployment as the server and webapp. The old names have no aliases:

| Was | Now |
| --- | --- |
| AI purpose `PRACTICE_DETECTION` | `PRACTICE_REVIEW` |
| `evidenceRequirements` | `automatedReviewPolicy` |
| `evidenceSupport` | `evidenceSufficiency` |
| practice `active` and `/active` | `reviewTier` and `/review-tier` (see below) |
| practice-group `active` | `visibleInPracticeDashboards` |
| observation `artifactType` | `artifactKind` |
| observation `title` | `summary` |
| observation `reasoning` | `evidenceRationale` |
| observation `guidance` | `deliveredFeedback` |
| observation `claimStatus` | `claimCurrentness` |
| observation `confidence` | removed; it was not a calibrated measurement |

Database values and columns migrate automatically. This change removes ambiguous uses of “active,” “support,” and
“detection”; it preserves historical observation outcomes. The uncalibrated confidence values are removed.

#### 🔴 A practice has a review-autonomy setting, not an on/off switch

**Affected**: API clients that turn practices on or off.

`PATCH /workspaces/{slug}/practices/{practiceSlug}/used-in-new-reviews` with
`{"usedInNewReviews": true|false}` is now
`PATCH /workspaces/{slug}/practices/{practiceSlug}/review-tier` with `{"reviewTier": "..."}`. The
practice payload carries `reviewTier` instead of `usedInNewReviews`, and the catalogue list filter is
`?reviewTier=<TIER>` instead of `?usedInNewReviews=<bool>`. There are no aliases.

The settings are `OFF`, `PROPOSE` and `DELIVER`, in increasing order of what the system does on its
own. Existing data maps exactly and needs no decision from you: a practice that was used in new
reviews becomes `DELIVER`, one that was not becomes `OFF`, and the migration runs automatically.
`PROPOSE` is new ground, and it is the middle the boolean could not express — the review still runs
and every observation is still recorded, and nothing is sent to anyone. All three values are settable
at every level; a practice only lands on `PROPOSE` because somebody put it there.

Omitting `reviewTier`, or sending it as `null`, clears the setting so the practice follows its group,
and the group follows the workspace default.

#### 🟢 A workspace can restrict review to some branches and repositories

**Affected**: nobody, unless you want it. Workspaces that do not configure a scope are unchanged.

The practice-review settings resource carries a `reviewScope` of two exact-match lists,
`targetBranches` and `repositories`. An empty list means no restriction on that axis. Exact names
only — there are no glob patterns, and there is no path scope, because the changed files of a pull
request are not known at the point where the decision to review is made.

#### 🔴 The "Skip drafts" workspace setting is gone

**Affected**: deployments that set `PRACTICE_REVIEW_SKIP_DRAFTS`, and workspaces that had "Skip
drafts" switched on.

Remove `PRACTICE_REVIEW_SKIP_DRAFTS` from your environment; it is no longer read, and the workspace
toggle no longer appears. Whether a draft occasions a review is now stated by each practice's own
occasions rather than by a switch that silenced all of them at once. One shipped practice ("Ready and
traceable handoff") asks for drafts, so a workspace that previously skipped them will start seeing
that practice's feedback on draft pull requests; no other practice reviews a draft. The stored
per-workspace override is retained unread for one release and removed after that.

#### 🔴 Outline connections require approved origins

**Affected**: deployments that enable the Outline integration.

Set `HEPHAESTUS_INTEGRATION_OUTLINE_ALLOWED_ORIGINS` to the comma-separated HTTPS origins whose operator role,
region, transfer basis, retention, and AVV status have been reviewed. An empty list blocks Outline connections,
sync, webhook collection, evidence projection, and identity linking. Set the same value on server, worker, and
webhook roles and restart all three. Disconnect connections for removed origins; remove all grants for
`outline.documents` until residual mirrored data has been erased.

#### 🔴 Untouched instances start with Silent Mode engaged

**Affected**: deployments where the instance Silent Mode setting has never been explicitly changed.

The upgrade engages the instance-wide outbound brake before any new GitHub, GitLab, or Slack delivery
can leave the application. Practice review, persistence, synchronization, webhooks, OAuth, and administration
continue normally; suppressed feedback is recorded and is never replayed.

On production, verify each workspace's practice delivery settings and provider targets, then open
**Instance admin → Settings** and release Silent Mode. Leave it engaged on staging clones and during
disaster-recovery drills. Instances whose operator had already changed the setting keep that explicit
choice.

**API clients:** The Silent Mode update operation is now
`PATCH /admin/settings/silent-mode`. Replace calls to the removed `PUT` operation before upgrading.

#### 🔴 Practice-feedback delivery field renamed

**Affected**: API clients that read or write user settings, or consume account-export JSON.

The personal practice-feedback field was renamed from `aiReviewEnabled` to
`practiceFeedbackDeliveryEnabled` so the API matches its actual scope: issue, pull-request, and
merge-request comments plus related Slack reminders. There is no alias for the old field.

Update request and response handling for `/user/settings`, and update the `preferences` object in
account exports, before deploying the new release. No database or environment change is required.

#### 🔴 Workspace purge moved to the owner-only deletion endpoint

**Affected**: automation that sets a workspace status to `PURGED`.

`PATCH /workspaces/{slug}/status` now accepts only `ACTIVE` and `SUSPENDED`. Replace a purge request
with `DELETE /workspaces/{slug}` and authenticate as that workspace's owner. The old request returns
`409 Workspace lifecycle violation`.

#### 🔴 LLM provider configuration moved from env vars to the admin console

**Affected**: any deployment setting `HEPHAESTUS_WORKER_LLM_BASE_URL`, `HEPHAESTUS_WORKER_LLM_API_KEY`, `HEPHAESTUS_SANDBOX_LLM_PROXY_ENABLED`, or an `AGENT_DEFAULT_CONFIG_*` variable.

**Before**: the worker pod's LLM upstream/key were passed through env vars (`HEPHAESTUS_WORKER_LLM_BASE_URL` / `HEPHAESTUS_WORKER_LLM_API_KEY`), and the LLM proxy could be toggled per pod with `HEPHAESTUS_SANDBOX_LLM_PROXY_ENABLED` (`hephaestus.sandbox.llm-proxy.enabled`).

**After**: OpenAI and other OpenAI-compatible endpoints are registered at runtime under **Instance admin → AI models**, with an explicit Chat Completions or Responses API contract, per-model pricing, and optional sharing with workspaces. Workspaces can also connect their own compatible endpoint. The LLM proxy — the only path a sandbox has to a provider key — now runs automatically wherever the worker/sandbox capability is on (`hephaestus.runtime.worker.enabled`, default true), which is both the worker pod and the application-server replica that serves interactive mentor sandboxes. It has no standalone enable flag. The three env vars above are no longer read.

**Migration** — step 1 is a configuration edit, so make it in the same pass that sets the new
`IMAGE_TAG`. Steps 2–6 run against the upgraded instance, once it has booted and applied its schema
migration: that migration is what writes the deploy-log lines steps 4–6 quote, so start the new
version first and keep its startup log to hand.

1. Remove `HEPHAESTUS_WORKER_LLM_BASE_URL`, `HEPHAESTUS_WORKER_LLM_API_KEY`,
   `HEPHAESTUS_SANDBOX_LLM_PROXY_ENABLED`, and every `AGENT_DEFAULT_CONFIG_*` variable from your
   deployment. They are no longer read. Remove them by grepping your deployment configuration rather
   than relying on startup diagnostics.
2. Register your OpenAI-compatible endpoint(s) under Instance admin → AI models (or have a workspace admin connect their own under the workspace's Administration → AI models). Each page tests the connection before you save it, so you learn the endpoint answers without waiting for a review to fail.
3. **Review and re-enable each workspace's carried-over AI configuration.** The upgrade copies every
   agent configuration that was in use — endpoint, model name, encrypted API key, timeout,
   concurrent-run limit and internet setting — into that workspace's AI models page, named after the
   old configuration. "In use" means one a workspace explicitly pointed at **or** any configuration
   that was simply switched on: an unset pointer never meant unused, it meant *fall back*, and the
   mentor fell back to the workspace's oldest enabled configuration while practice review ran on
   every enabled one. Configurations created from `AGENT_DEFAULT_CONFIG_*` are exactly that shape.
   No key you were using has to be re-issued. Everything arrives **disabled**, so practice review
   and the mentor stay stopped until an administrator opens the page and switches them on. That is
   deliberate: in the default PROXY credential mode the endpoint a configuration actually called came
   from an instance-wide environment variable rather than from the configuration row, so re-enabling
   automatically could silently re-point a workspace's traffic — and its key — at a different host.
   Until someone does, that workspace's practice review and mentor are simply idle: nothing errors,
   so there is nothing to notice. A workspace is done when its AI models page shows an enabled
   connection and a model bound to each purpose it uses.
4. **Fix what the upgrade could not determine.** These cases need a value typed in before they will
   work, and the deploy log names the affected workspaces (`AI configuration carried over with a
   placeholder endpoint, model id or non-OpenAI protocol in these workspaces: …`):
   - A configuration whose provider was **Azure OpenAI** with no base URL recorded: no
     instance-independent endpoint exists for it, so the carried-over connection holds the
     placeholder `https://endpoint-not-migrated.invalid/v1`. Replace it with your Azure resource URL.
   - A configuration whose provider was **Anthropic**: the new catalog speaks the OpenAI Chat
     Completions and Responses contracts only. Its key is preserved, but you need an
     OpenAI-compatible endpoint (or a gateway in front of Anthropic) for it to run.
   - A configuration that **never named a model**: the model carries the placeholder id
     `model-not-migrated`, which keeps the configuration's timeout, concurrency and internet limits
     attached to a real binding. Replace it with the model id you want. Such a configuration could
     not run before the upgrade either.
5. **Check any workspace where review ran on several configurations at once.** A workspace with no
   explicit practice-review pointer ran reviews on *every* enabled configuration. The new model binds
   one model per purpose, so practice review is bound to the oldest of them and the deploy log names
   the workspace. That log line is written by the migration itself and still uses this release's old
   word for the purpose: `practice detection ran on SEVERAL configurations at once in these
   workspaces: …`.
   Nothing is lost — the other configurations are all there as connections and models — but pick the
   one you want, or delete the rest.
6. **Revoke the keys of configurations that are dropped.** A configuration that was both switched off
   *and* unreferenced is not carried over: nothing could reach it, so it configured nothing. It is
   dropped with the old table, and the deploy log lists each one as `workspace/name` so you can
   revoke its API key at the provider if you want to.

#### 🔴 Agent job queue moved from NATS to PostgreSQL

**Affected**: any deployment setting `AGENT_NATS_ENABLED`, `HEPHAESTUS_AGENT_NATS_SERVER`, `AGENT_NATS_MAX_ACK_PENDING`, or `AGENT_NATS_FETCH_BATCH_SIZE`.

**Before**: the practice-review agent job queue was delivered over a NATS JetStream stream (`AGENT`); a worker pulled a job id off the stream, then loaded the job from PostgreSQL to execute it. Interactive mentor turns were and remain request-affine; they do not use `agent_job`.

**After**: workers poll `agent_job` directly and claim a batch with `FOR UPDATE SKIP LOCKED` — PostgreSQL, already the source of truth for job state, is now also the delivery mechanism. `AGENT_NATS_ENABLED` is replaced by `AGENT_ENABLED` (default `false`). New optional tuning: `AGENT_POLL_INTERVAL` (default `1s`), `AGENT_CLAIM_BATCH_SIZE` (default `5`), `AGENT_MAX_RETRIES` (default `5`), `AGENT_PAYLOAD_RETENTION` (default `P14D`), and `AGENT_ROW_RETENTION` (default `P90D`). NATS itself is unaffected everywhere else — it remains required for webhook ingest and SCM/Slack sync. See [ADR 0025](https://github.com/ls1intum/Hephaestus/blob/main/docs/decisions/0025-agent-job-queue-on-postgresql.md).

**Migration**:

1. Set `AGENT_ENABLED=true` (replacing `AGENT_NATS_ENABLED=true`) on **every** role that needs to submit, execute, or recover jobs — not just the role that claims and runs them. In a split-pod deployment that means **both** `application-server` (submits jobs from PR/issue events and runs the orphan-recovery sweep — both gate on this same flag, independent of the worker role) **and** `application-worker` (claims and executes them, additionally gated on the worker role); `docker/compose.app.yaml` already sets the same `AGENT_ENABLED` value on both services. In the monolith, set it once. No profile turns it on for you: a pod you do not set it on claims nothing — including a `worker`-profile pod you start outside the shipped Compose files, which in earlier releases turned itself on.
2. Confirm the flag actually took, on each side. Once the upgraded server is up, the `agent.queue.depth`, `agent.queue.oldest_age_seconds` and `agent.queue.running` metrics exist; if `AGENT_ENABLED` never reached that pod they are absent altogether rather than reading zero — which is the difference between "the queue is idle" and "the queue was never switched on". For the worker side, open a pull request and watch `agent.queue.oldest_age_seconds`: it should rise and fall. An age that only ever climbs means the server is submitting and no worker is claiming.
3. Remove `AGENT_NATS_ENABLED`, `AGENT_NATS_MAX_ACK_PENDING`, and `AGENT_NATS_FETCH_BATCH_SIZE` from your deployment. They are no longer read. Leave `NATS_SERVER` alone: it is still live for webhook and sync ingest.
4. Optional cleanup, after the upgraded instance has run long enough that you are not rolling back: the `AGENT` JetStream stream is no longer read from or written to. Delete it with `nats stream rm AGENT` if you want to reclaim its storage; leaving it in place is harmless.
5. Do not remove NATS itself or `NATS_ENABLED` — webhook ingest and SCM/Slack sync still require it.

#### 🔴 AI endpoints renamed to one vocabulary

**Affected**: any script or integration calling the workspace AI, agent-job, or LLM spend endpoints. No
action is needed for the web UI, which ships updated in the same release.

**Before**: the AI area of the API was `agent-configs` (named execution profiles), `ai-settings` (an
aggregate container that also held the practice-review policy and the two config pointers), and
`agent-jobs`.

**After**: every address is either `llm/…` (models and what they cost) or `agents/…` (the things that
run them). `agent-configs` and `ai-settings` are gone: a workspace's AI setup is a per-purpose binding
under `agents/…`, and the practice-review policy moved next to the practice catalogue. Both monthly
spend caps are `…/llm/budget` with the body `{ "monthlyBudgetUsd": … }`, addressing a workspace by
slug.

**There are no redirects or aliases.** An old address returns 404.

| Was | Now |
| --- | --- |
| `GET /workspaces/{slug}/agent-jobs[/{jobId}[/cancel\|/delivery/retry]]` | `GET /workspaces/{slug}/agents/jobs[/…]` |
| `GET /workspaces/{slug}/ai-settings` | `GET /workspaces/{slug}/practices/review-settings` |
| `PATCH /workspaces/{slug}/ai-settings/practice-review` | `PATCH /workspaces/{slug}/practices/review-settings` |
| `GET`/`POST` `/workspaces/{slug}/agent-configs`, `GET`/`PATCH`/`DELETE` `…/agent-configs/{configId}` | removed — see the AI-configuration section above |
| `PUT /workspaces/{slug}/ai-settings/practice-config`, `PUT …/ai-settings/mentor-config` | removed — see the AI-configuration section above |

Everything else in the AI area is **new** in this release, not a renamed address, so no existing call
site points at it:

| New | What it is |
| --- | --- |
| `GET /workspaces/{slug}/agents` | list a workspace's per-purpose bindings |
| `PUT`/`DELETE /workspaces/{slug}/agents/{purpose}` | set or clear one binding (`{purpose}` has no `GET`) |
| `GET /admin/llm/usage` | instance-wide LLM spend, `{ month, fx, workspaces: [...] }` |
| `PUT /admin/workspaces/{slug}/llm/budget` | the instance admin's monthly cap on a workspace |
| `GET /workspaces/{slug}/llm/usage` | that workspace's own spend view |
| `PUT /workspaces/{slug}/llm/budget` | the workspace's cap on its own connected provider |
| `GET /workspaces/{slug}/llm/settings` | the instance LLM policy as it applies to this workspace |
| `GET`/`POST`/`PATCH`/`DELETE /admin/llm/connections`, `…/models` | the instance model catalogue |

**Migration**:

1. Update every call site in the first table to its new address. `server/openapi.yaml` is the
   authoritative list.
2. If you read `GET /ai-settings` for `practicesEnabled` / `mentorEnabled`, take them from the workspace
   itself (`GET /workspaces/{slug}`); the review-settings response returns the review policy only.
3. If you called `/agent-configs` or the two `ai-settings` config pointers, follow the AI-configuration
   section above: a workspace now has exactly one binding per purpose, edited through
   `PUT /workspaces/{slug}/agents/{purpose}`.

No database action is required. The config-audit trail keeps its historical entity-type values as
written — the table is append-only by database trigger, so past rows are never rewritten.

#### 🔴 The default GitLab server follows your GitLab login instead of the maintainers' instance

**Affected**: deployments that use the shipped Compose files, talk to a GitLab other than
`gitlab.com`, and have never set `GITLAB_DEFAULT_SERVER_URL` themselves. Check with
`grep GITLAB_ .env` before you upgrade.

**Before**: `docker/compose.app.yaml` passed `GITLAB_DEFAULT_SERVER_URL:-https://gitlab.lrz.de` — the
maintainers' own university instance. Because Compose always supplied a concrete value, the fallback
the shipped configuration documents never ran, and an operator who had configured only their GitLab
login silently got an instance they had never named.

**After**: the same line is `${GITLAB_DEFAULT_SERVER_URL:-${GITLAB_OAUTH_BASE_URL:-https://gitlab.com}}`.
Unset, it follows your GitLab login URL; with neither set it is `https://gitlab.com`.

This setting names the GitLab that workspace creation and repository, group and member sync talk to.
Sync resolves its provider by that URL, so changing it does not re-point existing data — it stops
matching the rows written under the old URL and begins writing new ones stamped with the new one.

**Migration**:

1. If `GITLAB_DEFAULT_SERVER_URL` is set in your `.env`, nothing changes for you.
2. If it is unset but `GITLAB_OAUTH_BASE_URL` already names your GitLab, that is now the value —
   which is the intended behaviour, and for most self-hosted installs is the correct one. Confirm it
   is the instance you sync from.
3. If both are unset and you are not on `gitlab.com`, set `GITLAB_DEFAULT_SERVER_URL` to your
   instance **before** starting the new version.

A deployment that runs the application without the shipped Compose files was already defaulting to
`https://gitlab.com` and is unaffected.

#### 🔴 An agent heartbeat slower than 30 seconds now refuses to start

**Affected**: deployments that override `hephaestus.agent.heartbeat-interval`. There is no environment
variable for it, so that means an `application-local.yml` or another `spring.config.import` source,
or the relaxed-binding form `HEPHAESTUS_AGENT_HEARTBEATINTERVAL`. The shipped default is `25s` and is
valid; if you have not set this, there is nothing to do.

A worker renews a 60-second lease on the jobs it is running. A heartbeat slower than half that lease
let a worker be declared dead while it was still working: its in-flight reviews were requeued onto a
sibling and the same work ran twice, at double the model spend. The value is now rejected instead of
accepted.

**Migration**: if you set it above `30s`, lower it before upgrading. Otherwise the application does
not start — on every role, not only the worker, because the value is rejected when configuration is
bound — and reports:

```
hephaestus.agent.heartbeat-interval must be <= PT30S (half the PT1M worker lease), or every worker
is orphaned while its jobs are still running; got: ...
```

#### 🔴 The containers now have their own memory limits

**Affected**: hosts with less RAM than the limits add up to — in particular any host sized from
guidance that named 4 GB as the floor.

`application-server` (`APPLICATION_SERVER_MEM_LIMIT`, default `5g`), `application-worker`
(`APPLICATION_WORKER_MEM_LIMIT`, default `3g`) and `webhook-server` (`WEBHOOK_SERVER_MEM_LIMIT`,
default `2g`) each carry a container memory limit, and each JVM now sizes its heap from its own limit
rather than from the whole host. On the single-host install the worker runs inside the application
server, so the limits that apply there are `5g` and `2g`.

Nothing compares these against the host. Docker starts the stack either way and the kernel kills
whichever container exceeds its own limit; all three restart automatically, so an undersized host
presents as a restart loop rather than as a refusal to start. Review sandboxes are separate
containers and their memory sits outside these limits (`SANDBOX_MEMORY_BYTES`, default 4 GiB per
concurrent sandbox).

**Migration**: on a host below 8 GB RAM, set lower values in `.env` before the first start of the new
version. The [install guide](https://ls1intum.github.io/Hephaestus/admin/install) states the floor
and how the limits relate to it.

#### 🔴 A workspace's per-run AI timeout is capped at one hour

**Affected**: workspaces whose per-run timeout under Administration → AI models is above 3600 seconds.

The ceiling is enforced when the value is saved, and existing stored values are left as they are.
That combination is what needs your attention: such a workspace cannot save **any** change on its AI
models page until the timeout is brought to 3600 or below, because the whole form is rejected. Its
mentor turns are clamped to the ceiling, but its practice-review runs still run to the stored value,
so the setting and the behaviour disagree until you change it.

**Migration**: open each workspace's Administration → AI models page and lower any timeout above one
hour. There is no automatic clamp of stored values.

#### 🔴 AI proxy metrics are labelled by API contract, not by provider

**Affected**: deployments with dashboards or alerts on the LLM proxy metrics, and anything searching
logs by the `proxy.provider` field.

`llm.proxy.duration` and `llm.proxy.errors` keep their names. Their label changes from `provider`
(values `OPENAI`, `ANTHROPIC`, `AZURE_OPENAI`) to `apiProtocol` (values `openai-completions`,
`openai-responses`), because a provider name stopped identifying anything once any OpenAI-compatible
endpoint can be registered. The MDC log fields change from `proxy.jobId` and `proxy.provider` to
`proxy.principal` and `proxy.apiProtocol`.

A query filtering on the old label does not error — it matches no series and renders empty. An alert
built on one stops firing, which is indistinguishable from the condition being healthy.

**Migration**: update those queries before upgrading. New counters you may want to add while you are
there: `llm.proxy.budget.blocked` (calls refused by a spending cap), `llm.proxy.unbillable.refused`,
`llm.proxy.usage.unparseable` and `llm.proxy.stream.usage.unsupported` (responses whose token counts
could not be read, which is what makes a monthly total understated).

#### 🔴 Reviewed work is renamed in place, and the rename is one way

**Affected**: every deployment, and any API client that reads or writes the kind of work a practice
applies to or that a review or observation records.

A practice, a review run and a recorded observation all now identify what was reviewed as
`scm.pull_request`, `scm.issue` or `chat.conversation_thread`, replacing two internal vocabularies
that had drifted apart. The upgrade rewrites the stored values.

**Rolling the release back requires rolling this database change back with it.** Redeploying the
previous image on its own leaves a database the old version cannot read.

Two effects are worth expecting while the first reviews run after the upgrade, neither of which needs
action:

- A piece of feedback already posted on an open pull request or thread may be posted once more rather
  than updated in place. What ties a re-review to an earlier one is derived from the old name, so the
  first review after the upgrade does not recognise its own earlier comment.
- Practice review rules are re-fingerprinted on the first start after the upgrade, so a practice can
  briefly show as differing from its Hephaestus default until that finishes. If a workspace still
  shows as locally edited long afterwards, check the startup log: the pass records a failure per
  workspace and moves on rather than stopping, so a workspace it could not complete stays that way
  until the next start.

### v0.69.0

#### 🔴 Agent image pin moved from `docker/agent-image-pin.env` to a signed release asset

**Version**: v0.69.0
**Affected**: any deployment relying on `docker/agent-image-pin.env` or `docker/agent-image-pin.local.env`.

**Before**: `docker/agent-image-pin.env` was committed to `main` on every release by an auto-commit step in `release.yml`. `compose.app.yaml` loaded it via `env_file:` from the source tree.

**After**: each GitHub Release publishes a signed `release-vX.Y.Z.yaml` (cosign keyless OIDC bundle, multi-subject in-toto attestation). The `release-pin-fetcher` init service in `compose.app.yaml` fetches + verifies it at deploy time onto a shared volume; `application-server` imports it via `spring.config.import: optional:file:/pin/release-pin.yaml` (declared in `application-prod.yml`).

**Migration**:

1. Deploy host must reach `github.com`, `fulcio.sigstore.dev`, `rekor.sigstore.dev`, and `tuf-repo-cdn.sigstore.dev` over HTTPS.
2. Remove `docker/agent-image-pin.local.env`. Use `application-local.yml` or a shell env var instead — see [Release image lock](https://github.com/ls1intum/Hephaestus/blob/main/docs/admin/release-image-lock.md).
3. Confirm `HEPHAESTUS_AGENT_IMAGE_REFERENCE` is not pre-set in your deploy substrate; an unintended value shadows the verified pin.
4. Rolling back to a pre-v0.69.0 release: set `HEPHAESTUS_RELEASE_PIN_SKIP=true` plus an explicit `HEPHAESTUS_AGENT_IMAGE_REFERENCE=...@sha256:<digest>` env override on the init service.

#### 🔴 Agent runtime: image config consolidated under `hephaestus.agent.image.*`

**Version**: v0.69.0
**Affected**: any deployment that pinned the agent-pi image via `HEPHAESTUS_AGENT_PI_IMAGE`, `HEPHAESTUS_MENTOR_AGENT_IMAGE`, or the matching pull-policy env vars.

**Before**:

```bash
HEPHAESTUS_AGENT_PI_IMAGE=ghcr.io/ls1intum/hephaestus/agent-pi:latest
HEPHAESTUS_MENTOR_AGENT_IMAGE=ghcr.io/ls1intum/hephaestus/agent-pi:latest
HEPHAESTUS_AGENT_PI_PULL_POLICY=IF_NOT_PRESENT
HEPHAESTUS_MENTOR_AGENT_PULL_POLICY=IF_NOT_PRESENT
```

**After**: production binds `HEPHAESTUS_AGENT_IMAGE_REFERENCE` from the signed release asset (previous entry). `pull-policy` and `require-digest` are now Spring properties set in `application-prod.yml`, not env vars.

**Migration**:

1. Drop the four old env vars from your prod configuration.
2. See [Release image lock](https://github.com/ls1intum/Hephaestus/blob/main/docs/admin/release-image-lock.md) for verification + rollback.

---

## Automatic vs Manual Migrations

### Normal Startup Migrations

| Component | Tool | Notes |
|-----------|------|-------|
| Database schema | Liquibase | Follow the [database migration instructions](https://docs.hephaestus.build/admin/compatibility-policy#database-migrations) |

### Manual (Action Required)

| Component | How | Notes |
|-----------|-----|-------|
| Database cutover | Follow the target release's migration entry | Complete any required procedure before starting the new runtime roles |
| Environment variables | Check release notes | New config may be required |
| Helm chart | Check the chart values of the target release | Values may be added or renamed |

---

## Stability Roadmap

### v1.0.0 (Future)

The [Compatibility Policy](https://docs.hephaestus.build/admin/compatibility-policy) applies now,
including its pre-1.0 exception. Its stable compatibility and upgrade guarantees begin at v1.0.0.
Before then, minor releases can contain breaking changes.

---

## Common Migration Scenarios

### You build against our REST API

The API surface is published as `server/openapi.yaml` in each release. Regenerate your client from it
and review the release notes for endpoint changes.

### New Required Environment Variable

1. Check the release notes and the [Production Setup](https://docs.hephaestus.build/admin/production-setup) guide for new variables
2. Set the corresponding chart values and protected Secret keys
3. Run `helm upgrade` with the changed values, then restart the Pods that read a changed Secret key

### Database Schema Changed

First follow the [database migration instructions](https://docs.hephaestus.build/admin/compatibility-policy#database-migrations).
The chart's migration Job must complete before application roles start.
Save its first failure log before retrying:

```bash
kubectl -n hephaestus logs job/<migration-job> -c migration > migration.log
```

Liquibase normally runs each changeset in a transaction. A changeset with `runInTransaction="false"`
can fail after applying some statements. Earlier completed changesets also remain applied.
Do not assume that a failed upgrade rolled back or that the previous application can read its schema.

| Symptom in the log | What it means | What to do |
| --- | --- | --- |
| `Could not acquire change log lock` | A migration is active, or an interrupted process left its lock. | Follow [stale lock recovery](https://docs.hephaestus.build/admin/runtime-roles#one-migration-at-a-time) with the target release image. Never clear an active migration process's lock. |
| `Validation Failed: … checksums do not match` | A changelog file that already ran was edited. Released changelogs are immutable for exactly this reason. | Restore the file to its released content and fix forward with a *new* changelog. Do not `clearCheckSums` on production to make the error go away — it tells Liquibase to trust a file whose applied effect you no longer know. |
| A constraint or `NOT NULL` addition fails | Existing rows violate the new constraint. Migration tests do not cover every installed dataset. | Report the failing changeset and follow the release's recovery instructions. Do not bypass the constraint or mark a failed changeset as applied. |
| `permission denied` / `must be owner of` | The migration user lacks the required rights on an existing object. | Have the database owner restore the required ownership or permissions before retrying. |

**Follow the target release's recovery procedure.** A `<rollback>` block does not establish a supported
downgrade. Keep writers stopped if the release requires it. Choose one of these recovery paths:

1. **Apply a forward fix** qualified for the observed migration state. Do not restart an older image
   against a partially migrated database without an explicit supported procedure.
2. **Restore a verified backup** with its matching signed release lock and configuration. Follow
   [Backup & restore](https://docs.hephaestus.build/admin/backup-restore), including its key,
   privacy and restart checks. The matching lock prevents a failed target release from immediately
   migrating the restored database again.

Take a database dump before every upgrade that ships a migration. The release notes flag which ones
do.

---

## Getting Help

1. 📖 [GitHub Discussions](https://github.com/hephaestus-build/Hephaestus/discussions) - Ask the community
2. 🐛 [Issues](https://github.com/hephaestus-build/Hephaestus/issues) - Report problems
3. 📝 [CHANGELOG.md](./CHANGELOG.md) - Detailed change history
4. 🔄 [Release Notes](https://github.com/hephaestus-build/Hephaestus/releases) - Per-version details
