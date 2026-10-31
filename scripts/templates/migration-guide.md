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

<!-- VERSION_HISTORY -->

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
3. Restart services

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
   [Backup & restore](https://docs.hephaestus.build/admin/backup-restore), including its key, broker,
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
