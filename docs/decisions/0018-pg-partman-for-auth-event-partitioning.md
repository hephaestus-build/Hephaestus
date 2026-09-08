# ADR 0018: pg_partman for `auth_event` partitioning (supersedes ADR 0017's self-managed partitions)

**Status:** Accepted
**Date:** 2026-06-09
**Authors:** Felix T.J. Dietrich
**Supersedes (partition sub-decision only):** [ADR 0017](0017-replace-keycloak-with-spring-native-auth.md)
**PostgreSQL version superseded by:** [ADR 0038](0038-postgresql-18-release-baseline.md)

## Context

ADR 0017 introduced `auth_event`, a monthly RANGE-partitioned audit log, and — to keep it running on
**stock Postgres with no extension** — managed its partitions with a bespoke Java bean
(`AuthEventPartitionManager`): a `@Scheduled` + ShedLock job plus a Liquibase `DO` block that
hand-created `auth_event_pYYYYMM` partitions (create-ahead) and `DROP`ped expired ones (12-month
retention), with an `auth_event_default` catch-all.

That was ~280 lines of bespoke DDL re-implementing what `pg_partman` — the de-facto OSS standard —
does. The "no extension" constraint held only because we did not control the Postgres image. We now
do: every environment can run a custom image.

## Decision

Adopt **pg_partman 5.x** for `auth_event`, and delete `AuthEventPartitionManager`.

- **Image.** One thin Debian image, `ghcr.io/ls1intum/hephaestus/postgres` (`docker/postgres/Dockerfile`,
  `postgres:17-bookworm` + pinned `postgresql-17-partman`), used identically in dev, preview, and prod.
  This also retires preview's inconsistent `postgres:17-alpine` (Alpine has no `pg_partman` package).
- **Postgres major: 17** (GA, supported to Nov 2029; preview already ran it). 18 is a separate, later
  decision — not bundled with "new extension + new image + manager deletion".
- **Definition.** Liquibase changeset `1780825201546-18` runs `partman.create_parent` on `auth_event`
  (monthly RANGE, `premake=2`) and sets `part_config` retention to 12 months with hard `DROP`.
- **Scheduling: in-app `CALL partman.run_maintenance_proc()`** from a ShedLock'd, server-role
  `@Scheduled` bean (`AuthEventPartitionMaintenance`) — **not** the `pg_partman_bgw` background worker
  and **not** `pg_cron`. This deliberately avoids `shared_preload_libraries`: the BGW must be preloaded
  *before* the first `CREATE EXTENSION` or maintenance silently never runs. Keeping maintenance as plain
  SQL in the app's own scheduler keeps the image "stock Postgres + one extension", reuses the proven
  scheduler pattern, and keeps maintenance observable in app logs.

## Consequences

- Partitions are now named `auth_event_pYYYYMMDD` (pg_partman's convention) and the default partition is
  partman-owned. The JPA-vs-DB drift gate already excludes `auth_event_default` / `auth_event_p\d+`;
  pg_partman's own objects live in the `partman` schema and are not in the diffed `public` schema, so no
  new exclusions are required. The ERD generator already filters partition children + non-`public`
  schemas, so the diagram is unchanged.
- Retention's `DROP TABLE` is DDL, so it is unaffected by `auth_event`'s `BEFORE UPDATE OR DELETE`
  immutability trigger (which only blocks row mutation).
- Greenfield: this lands in the unmerged ADR-0017 branch, so there is **no data to migrate** — the
  bespoke partitions/manager are simply replaced. Reversibility: `AuthEventPartitionManager` remains in
  git history, and a misbehaving partman still accepts writes via the default partition.

## Validated

Built the image locally; applied the full changelog via `liquibase:update`; confirmed the extension
loads, `create_parent` builds current+2 ahead with a default, an insert lands in a real monthly child
(not the default), and `run_maintenance_proc()` runs — all with no `shared_preload_libraries`.
