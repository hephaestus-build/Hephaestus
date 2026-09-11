# Migration Guide

This document helps you upgrade between versions of Hephaestus. For what a version number promises
(public contract, upgrade guarantee, support statement), see the
[Compatibility Policy](https://docs.hephaestus.build/admin/compatibility-policy).

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
| `0.x.y` → `0.x.z` | Bug fixes, minor features |

### Upgrade Checklist

Before upgrading to any new `0.x.0` version:

1. ✅ Read the [release notes](https://github.com/hephaestus-build/Hephaestus/releases)
2. ✅ Check this migration guide for breaking changes
3. ✅ Verify in staging first (auto-deployed on every release)
4. ✅ Approve production deployment after staging verification

---

## Version History

Entries exist only for releases that need operator action. Everything else is in the
[release notes](https://github.com/hephaestus-build/Hephaestus/releases).

<!-- VERSION_HISTORY -->

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

At v1.0.0 the [Compatibility Policy](https://docs.hephaestus.build/admin/compatibility-policy)
takes effect — the public contract, the "any 1.x → any later 1.y" upgrade guarantee,
deprecation-ahead-of-removal, and latest-release-only support. Until then, expect rapid iteration and
occasional breaking changes in minor releases.

---

## Common Migration Scenarios

### You build against our REST API

The API surface is published as `server/openapi.yaml` in each release; regenerate your client from it
and review the release notes for endpoint changes.

### New Required Environment Variable

1. Check the release notes and the [Production Setup](https://docs.hephaestus.build/admin/production-setup) guide for new variables
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
   [Backup & restore](https://docs.hephaestus.build/admin/backup-restore); restore the
   database dump *and* the `.env` holding `HEPHAESTUS_SECURITY_ENCRYPTION_KEY`, or every encrypted
   credential in the restored database is unreadable. Then pin `IMAGE_TAG` to the version the dump
   was taken under so it is not immediately re-migrated by the release that failed.

Take a database dump before every upgrade that ships a migration. The release notes flag which ones
do.

---

## Getting Help

1. 📖 [GitHub Discussions](https://github.com/hephaestus-build/Hephaestus/discussions) - Ask the community
2. 🐛 [Issues](https://github.com/hephaestus-build/Hephaestus/issues) - Report problems
3. 📝 [CHANGELOG.md](./CHANGELOG.md) - Detailed change history
4. 🔄 [Release Notes](https://github.com/hephaestus-build/Hephaestus/releases) - Per-version details
