# Migration Guide

This document helps you upgrade between versions of Hephaestus. For what a version number promises
(public contract, upgrade guarantee, support statement), see the
[Compatibility Policy](https://ls1intum.github.io/Hephaestus/admin/compatibility-policy).

> ⚠️ **Pre-1.0 Notice**: We are in active development. Minor versions (0.x.0) may contain breaking changes. Always test in staging before production.

## Quick Reference

| Symbol | Meaning |
|--------|---------|
| 🔴 | **Breaking**: Action required before upgrade |
| 🟡 | **Deprecated**: Works now; removed in a later release — the release notes say which |
| 🟢 | **New**: No action needed |

## Check Your Version

Run these from the directory you deploy from — for a self-hosted install that is
`/opt/hephaestus/docker/self-host`, the same directory as your `.env`. Running them from the
repository root points at a different Compose project and reports nothing.

```bash
# Deployed version (the image tag your containers run; APP_VERSION is derived from it)
docker compose images application-server

# Latest release
curl -fsSL https://api.github.com/repos/ls1intum/Hephaestus/releases/latest \
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
| `0.x.y` → `0.x.z` | Bug fixes, minor features |

### Upgrade Checklist

Before upgrading to any new `0.x.0` version:

1. ✅ Read the [release notes](https://github.com/ls1intum/Hephaestus/releases)
2. ✅ Check this migration guide for breaking changes
3. ✅ Verify in staging first (auto-deployed on every release)
4. ✅ Approve production deployment after staging verification

---

## Version History

Entries exist only for releases that need operator action. Everything else is in the
[release notes](https://github.com/ls1intum/Hephaestus/releases).

### Next release

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

### Automatic (No Action Needed)

| Component | Tool | Notes |
|-----------|------|-------|
| Database schema | Liquibase | Changesets apply automatically, in order, on server startup |

### Manual (Action Required)

| Component | How | Notes |
|-----------|-----|-------|
| Environment variables | Check release notes | New config may be required |
| Docker compose | Check `docker/` files | Image versions may change |

---

## Stability Roadmap

### v1.0.0 (Future)

At v1.0.0 the [Compatibility Policy](https://ls1intum.github.io/Hephaestus/admin/compatibility-policy)
takes effect — the public contract, the "any 1.x → any later 1.y" upgrade guarantee,
deprecation-ahead-of-removal, and latest-release-only support. Until then, expect rapid iteration and
occasional breaking changes in minor releases.

---

## Common Migration Scenarios

### You build against our REST API

The API surface is published as `server/openapi.yaml` in each release; regenerate your client from it
and review the release notes for endpoint changes.

### New Required Environment Variable

1. Check the release notes and the [Production Setup](https://ls1intum.github.io/Hephaestus/admin/production-setup) guide for new variables
2. Add them to your deployment's environment (see the `docker/compose.app.yaml` env block)
3. Restart services

### Database Schema Changed

Liquibase applies changelogs automatically, in order, on server startup. A failure is not silent: the
application context fails to start, so the container exits and your orchestrator restarts it — a
crash-loop whose first useful line is in the *first* startup attempt's log, not the latest. Capture
that before doing anything else:

```bash
docker compose logs application-server | grep -i -m5 liquibase
```

Liquibase runs each changeset in its own transaction, so a failure leaves earlier changesets applied
and the failing one rolled back. The database is therefore consistent but *partially migrated*, and
the old application version may no longer match it — do not assume rolling back the image is safe.

| Symptom in the log | What it means | What to do |
| --- | --- | --- |
| `Could not acquire change log lock` | A previous run was killed (OOM, `docker kill`, node eviction) mid-migration and left its row in `DATABASECHANGELOGLOCK`. | Confirm no server is actually running, then clear it: `UPDATE databasechangeloglock SET locked = FALSE, lockgranted = NULL, lockedby = NULL WHERE id = 1;` and restart. Never clear it while another replica may still be migrating. |
| `Validation Failed: … checksums do not match` | A changelog file that already ran was edited. Released changelogs are immutable for exactly this reason. | Restore the file to its released content and fix forward with a *new* changelog. Do not `clearCheckSums` on production to make the error go away — it tells Liquibase to trust a file whose applied effect you no longer know. |
| A constraint or `NOT NULL` addition fails | Existing rows violate the new rule. CI replays migrations against an empty database, so a data-incompatible migration passes CI and fails only on real data. | Do not hand-edit the schema. Report it with the failing changeset id; the fix ships as a new changelog that cleans the data first. |
| `permission denied` / `must be owner of` | The database user lacks DDL rights on an existing object. | Grant ownership or `ALTER` on the named object and restart. |

**Fix forward, do not roll back.** Changelogs carry `<rollback>` blocks, but they are never exercised
in CI and are not a supported recovery path. The supported recoveries, in order of preference:

1. **Wait for a patch release** that fixes the changeset forward. A partially migrated database keeps
   serving on the previous image only if no applied changeset broke it — check the log for which
   changesets succeeded before deciding.
2. **Restore from backup** if the instance must come back now and forward-fixing will take longer than
   the outage budget. Follow
   [Backup & restore](https://ls1intum.github.io/Hephaestus/admin/backup-restore); restore the
   database dump *and* the `.env` holding `HEPHAESTUS_SECURITY_ENCRYPTION_KEY`, or every encrypted
   credential in the restored database is unreadable. Then pin `IMAGE_TAG` to the version the dump
   was taken under so it is not immediately re-migrated by the release that failed.

Take a database dump before every upgrade that ships a migration. The release notes flag which ones
do.

---

## Getting Help

1. 📖 [GitHub Discussions](https://github.com/ls1intum/Hephaestus/discussions) - Ask the community
2. 🐛 [Issues](https://github.com/ls1intum/Hephaestus/issues) - Report problems
3. 📝 [CHANGELOG.md](./CHANGELOG.md) - Detailed change history
4. 🔄 [Release Notes](https://github.com/ls1intum/Hephaestus/releases) - Per-version details
