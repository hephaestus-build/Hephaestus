---
title: Unified integration migration
description: The one-off cutover to the unified integration framework, kept for deployments that predate it.
---

# Migration guide — Unified integration framework (#1198 / PR #1306)

Actionable, post-merge migration steps for operators upgrading from pre-#1198 releases to the unified
`integration/{core,scm}` framework. Steps marked **VERIFIED** were exercised at runtime against a
fresh database and a live GitHub App; steps marked **MANUAL** require an operator
action that cannot be automated by the deploy.

**Read [§5](#5-scm-disconnect-now-erases-the-mirror-behaviour-change) before
disconnecting anything.** A later release made SCM disconnect and workspace purge erase the mirrored
data; it is irreversible.

## What changed (operator-relevant)

- `gitprovider/**` → `integration/{core,scm}/**`; per-provider credentials now live in a single
  encrypted `connection` table (one row per workspace × kind × instance), replacing the
  `workspace.installation_id / personal_access_token / git_provider_mode / gitlab_* / slack_* /
  leaderboard_notification_*` columns.
- All SCM/Slack config consolidated under `hephaestus.integration.*` (was `hephaestus.scm.*` /
  `hephaestus.gitprovider.*` / standalone Slack props). The in-repo `application.yml` /
  `application-prod.yml` are already migrated — **only out-of-band overrides (env, k8s secrets,
  compose) need updating.**
- Inbound webhooks moved from `POST /github` & `POST /gitlab` to a single `POST /webhooks/{kind}`
  (`github`, `gitlab`). **This is a hard cutover — there is no 308 redirect from the old paths.**

## 1. Required environment before deploy (MANUAL)

Set these in the prod environment **before** rolling the new image — the migration re-encrypts
credentials and the app fails fast in `prod` if they are missing:

| Variable | Purpose | Notes |
|---|---|---|
| `HEPHAESTUS_SECURITY_ENCRYPTION_KEY` | AES-256-GCM key for credentials at rest | **Exactly 32 chars.** Required in `prod` (fail-fast). Keep it stable — rotating it requires re-encrypting all `connection` rows. |
| `WEBHOOK_SECRET` | Inbound webhook HMAC/token verification | Already required pre-#1198. Also backs OAuth-state HMAC unless `HEPHAESTUS_INTEGRATION_OAUTH_STATE_SECRET` is set. In non-`prod` an ephemeral secret is auto-generated (a WARN is logged) so local dev boots without it. |
| `HEPHAESTUS_INTEGRATION_SLACK_CLIENT_ID` / `_CLIENT_SECRET` / `_REDIRECT_URI` / `_SIGNING_SECRET` | Slack per-workspace OAuth install plus Events API/interactivity verification | The signing secret is required on the webhook-server when Slack is enabled. |

GitHub App (`GH_APP_ID`, `GH_APP_PRIVATE_KEY`) and GitLab (`hephaestus.integration.gitlab.*`)
env keys are unchanged in shape.

## 2. Database migration (VERIFIED — automatic)

Liquibase applies on startup. Verified on a fresh DB: all changesets apply cleanly and the
schema validates. The credential migration is changeset `1780313973588` →
`WorkspaceConnectionBackfillChange`:

1. **Backfills** each workspace's legacy credential columns into the encrypted `connection`
   table (`ON CONFLICT DO NOTHING` — idempotent, safe to re-run).
2. A **HALT guard** (`-21c`) aborts the migration if workspaces exist but no `connection` rows
   were produced *and* credentials were present — preventing the subsequent column drops from
   destroying un-migrated credentials. It does not trip when every workspace has a
   `NULL git_provider_mode`, i.e. genuinely nothing to migrate.
3. **Drops** the legacy `workspace.*` credential columns.

Because the encryption key is read during the backfill, **`HEPHAESTUS_SECURITY_ENCRYPTION_KEY`
must be set before the migration runs** (step 1). Rollback re-adds the dropped columns *empty* —
prefer roll-forward over rollback (expand/contract); a rollback would require re-entering creds.

## 3. Re-register webhook URLs after deploy (MANUAL — required)

The old `/github` and `/gitlab` routes are removed (no redirect). After the new image is live:

- **GitHub App:** set the App's *Webhook URL* to `https://<host>/webhooks/github`.
- **GitLab:** point project/group webhooks at `https://<host>/webhooks/gitlab`. New workspaces
  auto-register correctly via `GitLabWebhookService`; **existing** hooks pointing at `/gitlab`
  must be updated by hand (or re-run auto-registration).

Push events are not redelivered by the vendors, so expect a brief webhook gap during cutover; the
scheduled sync backfills anything missed.

## 4. Recommended deploy order

1. Set the env vars in §1 (especially `HEPHAESTUS_SECURITY_ENCRYPTION_KEY`).
2. Deploy the new image → Liquibase backfills `connection` then drops legacy columns.
3. Re-point GitHub App + GitLab webhook URLs to `/webhooks/{kind}` (§3).
4. Verify: a workspace's connection is `ACTIVE`, a manual sync pulls data, and a test webhook is
   received (`/actuator/health` on the webhook-server pod).

## 5. SCM disconnect now erases the mirror (behaviour change)

**This is destructive and has no undo.** It landed after #1198 and applies to any operator upgrading
past that release.

Previously, disconnecting a GitHub or GitLab connection left every mirrored row in PostgreSQL, and
purging a workspace cleared only its monitors and local clones. Now both actions run the same
erasure:

- Mirrored repositories and everything cascading from them (issues, pull/merge requests, reviews,
  review threads and comments, discussions, labels, milestones, collaborators) are **hard-deleted**,
  along with the workspace's repository monitors, its local git clones, its activity-event log, and
  the SCM-derived practice observations and feedback.
- Only repositories no other workspace still monitors are deleted. A repository shared with another
  workspace survives; the disconnecting workspace simply loses its access path to it.
- The org-level mirror (teams, team memberships, organisation memberships) is deleted only when no
  other non-purged workspace is bound to the same organisation.
- Retained deliberately: `sync_job`, `connection_activity`, `connection_audit` — operational history
  with no third-party content — so the disconnection stays auditable. Connection credentials are
  cleared as part of the same transition.

Consequences for operators:

- **Reconnecting is a fresh initial sync, not a restore.** Anything the vendor no longer has is
  gone. This matches the Slack and Outline behaviour, which have always erased on disconnect.
- **Do not use disconnect as a way to rotate credentials.** Re-enter the credential on the existing
  connection instead.
- Disconnect is refused with a retryable `409` while a sync job is in flight, so the erase never
  races a writer.

See [ADR 0024](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0024-integration-sync-lifecycle-and-two-deletion-semantics.md)
for the full model and [Integration sync lifecycle](./sync-lifecycle.md) for per-integration detail.

## 6. Explicitly NOT in this migration (so you don't wait for them)

- **No 308 redirect** from legacy `/github`·`/gitlab` (hard cutover — §3).
- **No canonical versioned `IntegrationEvent` wire envelope** — the in-process `ScmDomainEvent`
  hierarchy is used; the cross-vendor envelope is deferred (ADR 0015).
- **No anonymous-installation bootstrap table** (waived).
- **No JetStream DLQ** (ADR 0013 — by design).

> Live webhook delivery, GitLab, Slack OAuth, and LLM-review paths need a public tunnel + live
> OAuth apps to exercise. Use the steps above plus the service `AGENTS.md` runbooks to validate
> them in a staging environment.
