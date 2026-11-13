# ADR 0102: Sync-status hints use PostgreSQL notifications

**Status:** Accepted
**Date:** 2026-11-13
**Authors:** Felix T.J. Dietrich
**Amends:** [ADR 0080](0080-provider-events-go-to-a-postgresql-inbox.md) and [ADR 0025](0025-agent-job-queue-on-postgresql.md).

## Context

After provider events moved to PostgreSQL, a broker remained only for sync-status invalidations between server replicas.
The client already polls REST when a hint is missed. The hint needs no durable queue or content.

## Decision

Use PostgreSQL `LISTEN/NOTIFY` on `sync_status` and remove the broker and its deployment and configuration.
The payload contains only a workspace ID, connection ID and scope token, bounded to fewer than 128 ASCII bytes.
The client reads the data through REST. No provider payload, credential or personal content crosses this channel.
Every replica, including the origin, sends to its local SSE emitters only after receiving the notification.
A failed publish never delivers locally, because the database might have accepted it before the failure.

The listener owns one dedicated connection outside the application pool, with its own dispatch and reconnect loop.
PostgreSQL can listen to several channels on one session, but the existing listeners do not share a transport.
Their resync and failure handling differ. Sharing sync dispatch with a ledger or permit listener would couple their progress.
Keep those failure boundaries and count the fifth connection in the existing chart budget for every server in rolling overlap.

## Consequences

An operator runs no broker and keeps no broker credential, volume or port for sync hints.
A notification missed during reconnect is not replayed. The browser refreshes this section's REST queries every 60 seconds while visible, even when SSE stays connected.
Publishing uses the application pool briefly; one additional connection per server waits outside that pool.
No schema, SSE routing, provider adapter, or REST contract changes.

## Confirmation

`SyncPushServicePostgresIntegrationTest` sends a committed sync change through two PostgreSQL listeners and real SSE writers.
Each replica receives one hint; a rolled-back change sends none.
`use-sync-events.test.tsx` verifies REST catch-up after a missed hint on an open stream, scoped to the workspace's integration queries.
The chart render tests reject retired values, render no broker objects for any profile, and validate the fifth listener's connection budget.

## Sources

- [PostgreSQL LISTEN](https://www.postgresql.org/docs/current/sql-listen.html): subscriptions belong to a session and take effect at commit.
- [PostgreSQL NOTIFY](https://www.postgresql.org/docs/current/sql-notify.html): every listening session receives the notification, including the origin; payloads must be shorter than 8000 bytes and missed hints are not durable.

## Revisit trigger

Measured connection pressure justifies a shared listener transport with independent, bounded dispatch and resync for each channel.
