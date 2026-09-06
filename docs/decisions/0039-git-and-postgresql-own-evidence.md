# ADR 0039: Git owns repository evidence; PostgreSQL owns captured payloads and references

**Status:** Accepted
**Date:** 2026-08-30
**Authors:** Hephaestus maintainers
**Builds on:** [ADR 0020](0020-context-fabric-everything-is-an-integration.md),
[ADR 0025](0025-agent-job-queue-on-postgresql.md)

## Context

Practice-review evidence currently crosses three authorities. PostgreSQL retains a job manifest for
90 days, while a filesystem CAS retains cited bytes for 30 days. Delivery requires those bytes, so
the CAS is not a disposable cache. Its collector derives liveness by parsing filesystem manifests;
one unreadable manifest stops collection. Split roles consequently require a shared POSIX filesystem.

This contradicts ADR 0020's SQL-authority rule, gives one capture independent retention clocks, and
makes erasure depend on a filesystem scan. Issue
[#1427](https://github.com/hephaestus-build/Hephaestus/issues/1427) defined the required evidence invariants
without selecting storage; this ADR selects it for 1.0.

## Decision drivers

- A retained citation resolves to the captured bytes or a typed unreplayable outcome, never to
  substitute bytes or a malformed-evidence error caused by cache expiry.
- PostgreSQL is the queryable authority for tenancy, liveness, retention, and erasure.
- Server and worker roles share PostgreSQL, not an evidence volume.
- The design uses Git's object graph for repositories and avoids a third store or dual-read migration.

## Considered options

1. **Repair the filesystem CAS.** Rejected: it remains a third authority and shared-filesystem
   prerequisite, with liveness outside PostgreSQL.
2. **Store every byte in PostgreSQL.** Rejected for repository history: it duplicates Git's packing,
   reachability, and worktree model. PostgreSQL remains suitable for bounded API captures.
3. **Adopt S3-compatible storage.** Rejected until measurements justify another credential,
   availability boundary, lifecycle policy, and erasure reconciliation loop.
4. **Git for repository evidence and PostgreSQL for everything else (chosen).**

## Decision

### Authority and identity

- Each worker maintains a full local clone of every repository it captures. A capture pins its commit
  closure at `refs/hephaestus/keep/<jobId>`. Job IDs are globally unique. Git owns the reachable
  commit, tree, and blob objects; PostgreSQL owns the object-ID reference and its liveness.
- PostgreSQL owns manifests, evidence references, retention and erasure state, and bounded bytes
  captured through APIs, including pull-request data, comments, Slack threads, and Outline documents.
  Payloads use inline `bytea`, extending [#1103](https://github.com/hephaestus-build/Hephaestus/issues/1103).
- Commit object IDs and SHA-256 payload digests are the only evidence identities. Paths, URLs,
  repository IDs, source kinds, and provider types cannot participate in identity. Typed provenance
  and sandbox-relative locations may accompany a digest only as tenant-scoped metadata required to
  authorize retrieval and verify a citation; host paths and upstream URLs never cross the worker
  protocol. A digest proves integrity, not authorization.

There is no filesystem blob store, object-store SPI, S3 deployment, or compatibility reader. A clone
with a live keep-ref is evidence storage, not an evictable cache. Worktrees and job directories are
disposable projections.

The 1.0 topology has one worker storage node; multiple worker processes on that node share its clones
and locks. Adding independent worker storage nodes requires explicit placement and routing and is a
revisit, not an implicit capability of this decision.

### Publication, erasure, and collection

Git and PostgreSQL cannot share a transaction. Every process that mutates or prunes refs in one local
clone therefore uses the same inter-process clone lock:

1. under the lock, fetch the complete closure and create the previously absent keep-ref atomically;
   retry may confirm the same object ID but never move the ref;
2. in one PostgreSQL transaction, insert payloads, references, manifest, and the capture state that
   makes them visible;
3. release the lock and acknowledge only after commit.

A keep-ref without a live SQL job is SQL-orphaned and eligible for removal. Collection takes a
consistent PostgreSQL root set under the clone lock; it never infers liveness from disk. Publication
and erasure lock or conditionally update the same job state, so erasure either follows publication or
prevents it; a late writer cannot resurrect evidence.

SQL deletion revokes access to the job, manifest, references, and payloads transactionally. Payload
deduplication is workspace-local; bytes remain while another authorized job references them. Digest
lookup cannot reveal cross-workspace existence. Cleanup then removes job worktrees and temporary
files before its keep-ref, and Git reclaims unreferenced objects under its normal prune policy.
Keep-refs have no reflog. Cleanup is idempotent, retried, and measured; physical reclamation in Git,
PostgreSQL, WAL, replicas, and backups follows their documented retention rather than pretending to
be immediate secure deletion.

### Durability contract

Captured API payloads are durable for the job lifetime. Repository evidence is best-effort within
that window because local clones are not replicated. A lost clone may recover the commit only when an
ordinary authenticated upstream fetch can reach it. Otherwise replay and delivery use the existing
**unreplayable-legacy** outcome, never malformed evidence, and never current upstream content.

### Failure matrix

| Failure | Required outcome |
|---|---|
| Crash after ref creation, before SQL commit | No visible capture; collection may remove the SQL-orphaned ref. |
| SQL rollback after payload insertion | Payloads, references, and manifest roll back together; the ref is SQL-orphaned. |
| Crash after SQL commit, before acknowledgement | Retry observes the capture without duplicating payloads or moving the ref. |
| Clone lost while job is live | Recover only from the exact upstream object; otherwise unreplayable-legacy. |
| Upstream force-push or branch deletion | A live keep-ref preserves the closure; without it, loss is unreplayable-legacy. |
| Publish races with erasure | Publish then erase, or erase prevents visibility; evidence is never resurrected. |
| Collection races with publication | The clone lock and SQL root set preserve every published ref. |
| Cleanup fails at any step | Revoke access, retain excess bytes, report lag, and retry; never remove a live ref. |

### Materialization and limits

Each execution gets a disposable full Git worktree for every captured repository plus declared
PostgreSQL payloads in its temporary input directory. Worktrees are read-only to the network-isolated
sandbox and their cleanup does not affect keep-refs.

Repository evidence is not truncated by `GIT_TREE_MAX_*`. Those repository-tree limits are retired;
any API-payload limits use source-specific names, reject oversize input before publication, and expose
rejected-size and stored-size measurements. TOAST does not relax those limits.

For 1.0, a full clone has no promisor dependency for retained evidence. This supersedes
[#1102](https://github.com/hephaestus-build/Hephaestus/issues/1102)'s locked `--filter=blob:none` choice for the
evidence path. Partial clone may return in v2 only after an offline test proves every object reachable
from every live keep-ref remains locally available after restart and eviction consults SQL liveness.

### Completion criterion

The implementation is complete when isolated server and worker filesystems, sharing only one
PostgreSQL instance, capture, publish, deliver with exact-quote verification, erase, and collect one
case end to end. Automated tests verify no SQL-visible dangling evidence, no resurrection, idempotent
retry, conservative cleanup failure, and exact-byte delivery. Disposable
remotes cover lost-clone and force-push behavior; cleanup crash tests cover every ordered step.

Every surviving or replacement setting used by a runtime role must be represented in `ROLE_SCOPES`,
as required by ADR 0005's 2026-08-22 amendment.

## Consequences

- PostgreSQL backups cover all non-repository evidence and lifecycle metadata. Repository replay can
  end early after worker-disk loss, which is observable separately from corruption and insufficient
  evidence.
- Implementations maintain publication and cleanup across the Git/SQL boundary. Conservative cleanup
  failure consumes storage rather than deleting live evidence.
- Implementing this decision removes the CAS, filesystem manifests, shared evidence-volume contract,
  and compatibility reads in one cutover.

## Revisit trigger

Consider object storage when captured-payload p99 exceeds 100 MB or measured PostgreSQL payload growth
or backup/restore time breaches its documented service objective. A proposal must address tenancy,
encryption, transactional publication, retention, erasure, recovery, cost, and migration while
preserving one authority per object. Scale speculation is not a trigger.

Adding a second worker storage node requires measured demand plus a placement and routing design that
preserves the same failure matrix without shared evidence storage.

## Sources

- [Git partial clone](https://git-scm.com/docs/partial-clone)
- [Git `update-ref`](https://git-scm.com/docs/git-update-ref)
- [Git `worktree`](https://git-scm.com/docs/git-worktree)
- [Git garbage collection](https://git-scm.com/docs/git-gc)
- [PostgreSQL binary data types](https://www.postgresql.org/docs/18/datatype-binary.html)
- [PostgreSQL TOAST](https://www.postgresql.org/docs/18/storage-toast.html)
- [PostgreSQL transaction isolation](https://www.postgresql.org/docs/18/transaction-iso.html)

## Update — 2026-09-03 (issue #1719)

[ADR 0041](0041-compose-1x-kubernetes-2.md) supersedes § Publication, erasure, and collection,
§ Durability contract, and § Materialization and limits. Verification happens once at result
admission against the job folder the agent read; only the verdict survives.
