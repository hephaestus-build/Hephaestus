# ADR 0038: PostgreSQL 18 is the only supported major version

**Status:** Accepted

## Context

A single PostgreSQL major version keeps release, development, and CI behavior aligned. PostgreSQL
major-version data directories are not binary compatible. The PostgreSQL 18 image stores its
versioned `PGDATA` below `/var/lib/postgresql`, which determines the persistent volume mount.

## Decision

- Support PostgreSQL 18 only, including externally operated installations. Ship pg_partman 5.5
  with the repository-managed image; maintenance remains application-scheduled as decided in
  [ADR 0018](0018-pg-partman-for-auth-event-partitioning.md).
- Use the same PostgreSQL major for releases, local development, previews, and CI.
- Mount persistence at `/var/lib/postgresql`. Keep the name of the chart's PostgreSQL volume claim
  template (`data`) stable. A renamed volume can silently initialize an empty database.
  An incompatible existing cluster then stays hidden.
- Recover from a verified logical backup, not an in-place image swap. Rehearse backup and restore
  with `scripts/postgres-backup-restore-test.ts`.

## Consequences

Older PostgreSQL installations must migrate before deploying this release. The
[backup and restore guide](../admin/backup-restore.mdx#unsupported-postgresql-versions) links to the
[compatibility policy](../admin/compatibility-policy.mdx), which owns the upgrade prerequisites.
A successful restore rehearsal and an off-host backup are required before deleting a data volume; listing an archive does not prove recovery works.

## Confirmation

The global precondition in `db/master.xml` halts every migration on a PostgreSQL major other than 18.
The CI backup-restore drill (`scripts/postgres-backup-restore-test.ts`) fails when its source or target is not PostgreSQL 18.

## Sources

- [PostgreSQL versioning policy](https://www.postgresql.org/support/versioning/)
- [PostgreSQL major-upgrade documentation](https://www.postgresql.org/docs/18/upgrading.html)
- [Official image `PGDATA` contract](https://github.com/docker-library/docs/blob/master/postgres/README.md#pgdata)
- [pg_partman 5.5 release notes](https://github.com/pgpartman/pg_partman/releases/tag/v5.5.0)

## Update — 2026-11-11: the support policy after 1.0

An external PostgreSQL must be major version 18 for the 1.0 release.
After that release, Hephaestus supports the newest major version and the one before it. A CI leg tests the older one.
