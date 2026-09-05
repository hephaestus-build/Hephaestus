# ADR 0020: Context Fabric — everything is an integration, only practice review and mentor are native

**Status:** Accepted (amended 2026-08-04, 2026-08-30 and 2026-09-03 — see the updates below)
**Date:** 2026-06-12
**Authors:** Hephaestus maintainers
**Builds on:** [ADR 0015](0015-unified-integration-framework.md) (the integration framework and `Connection` aggregate), [ADR 0004](0004-sql-layer-tenancy-via-statement-inspector.md) (SQL-layer tenancy), [ADR 0014](0014-per-row-aes-gcm-aad-binding.md) (per-row AAD), [ADR 0007](0007-sandbox-spi-shape.md) (the agent sandbox / `ContentSource` seam)

## Context

The practice-review agent reviews a change from a materialised context directory
(`context/target/*`) built by the `ContentSource` SPI (`agent.context`, ADR 0007).
A directory that holds only what the diff and the PR row carry (`metadata.json`,
`diff.patch`, `diff_summary.md`, `comments.json`) makes a large fraction of mentor
lessons **context-blind** misses rather than review misses: the signal lives in
another artifact the agent never saw. Three sharp examples:

- A change that `Closes #N` could not be judged against #N's acceptance criteria,
  because the criteria live in the **issue body**, never in the diff.
- A branch cut from an in-flight feature branch (large ahead-range, multiple authors)
  was invisible, because that is a fact of the **branch graph**, not the patch.
- A "all tests pass" Definition-of-Done tick was treated as credible even in a repo
  that **has no test target at all**, because test-suite existence is a fact of the
  **repository tree**, not the diff.

The deeper reframe behind the fix: the SCM is not special. An issue body, a branch
graph, a test-target inventory, an Outline doc, a Slack thread — these are all the same
shape of thing: **external content, fetched on demand, projected into the agent's
context with provenance**. The only genuinely *native* capabilities Hephaestus owns are
**practice review** and **mentor**; everything that feeds them is an integration. We
call the substrate that makes any such content addressable, tenant-safe, and cacheable
the **Context Fabric**.

This ADR records the target architecture *and* the first, smallest slice of it that
ships on the existing seam.

## Considered options

1. Build the full Connector SPI plus CAS plus node/edge graph now. Rejected: pays the whole migration cost before the reframe has delivered one lesson.
2. Keep adding ad-hoc ContentSources forever. Rejected: a non-SCM connector would have no seam; naming the Fabric now makes the Connector promotion a forcing function, not a rewrite.
3. Ship the slice on the existing seam, target staged-additive (chosen). Reframe proven for one PR, zero schema change.

## Decision register

The Context Fabric is defined by the following decisions. Most are **target state**
(staged follow-ups); the shipped slice is called out in §"Shipped slice".

1. **SQL is the source of truth; disk is a rebuildable cache.** Persisted rows in
   Postgres are authoritative. Everything on the agent's disk (`context/target/*`, the
   git clone, any future projected doc) is a **derived, rebuildable cache** — deletable
   at any time and reconstructable from SQL + the upstream connector. No agent-visible
   file is ever a system-of-record. This is the same posture ADR 0004 takes toward
   tenancy: the row is the truth, the projection is a view.
2. **Content-addressable store (CAS), not "bronze."** The on-disk cache is keyed by
   content hash (generalising the existing git-clone + content-hash derivation). We
   deliberately do **not** call it a "bronze layer." The Databricks medallion vocabulary
   implies bronze is *raw landed truth*; here SQL is truth and the CAS is a derived
   cache, so importing "bronze" would invert the trust direction. See Evidence §Iceberg/
   Databricks for why the rename is load-bearing, not cosmetic.
3. **One `Connector` SPI, a superset of `ContentSource` (target state).** The target SPI is a single
   `Connector` interface that subsumes today's `ContentSource`: `supports(request)`,
   `required()`, and a `contribute`/`project` step, plus capability declaration and a
   fetch step against the upstream. SCM becomes a *peer* connector of Slack/Outline, not
   a privileged root. Today's `ContentSource` is the narrow, already-shipped face of
   that superset.
4. **Five PROV-O Kinds.** Projected content is typed by a small fixed vocabulary aligned
   to W3C PROV-O: **Entity** (a thing — issue, doc, commit), **Activity** (a process —
   a review, a sync run), **Agent** (a who — author, bot, service), **Bundle** (a
   provenance-scoped set), **Plan/Usage** (the recipe + the act of using it). Five Kinds,
   not an open-ended type zoo.
5. **`entity_node` / `entity_edge` with split confidence.** Cross-context links (PR ↔
   issue, doc ↔ MR, thread ↔ change) are modelled as a node/edge graph with **two
   separate confidences**: *match confidence* (how sure we are this is the right target)
   and *assertion confidence* (how sure we are the claimed relationship holds). Splitting
   them prevents a high-recall fuzzy match from masquerading as a high-precision
   assertion — the exact failure mode the linked-work-item provider guards against by
   never asserting an unmet criterion.
6. **Capability registry.** A connector declares which Kinds and which capabilities
   (feedback channel, observation channel, content fetch, link assertion) it supports; the
   builder gates work on the declaration rather than on `instanceof`. This is the
   generalisation of ADR 0015's manifest-gated SPI.
7. **Telescope manifest, not a cage.** Projected content is **hints + provenance, never
   verdicts and never full bodies**: lists are capped, bodies are excerpted, every item
   carries a clickable `url`. The manifest tells the agent what *exists* and where to look
   deeper; it never pre-renders a judgement. "Telescope, not cage" is the litmus the
   linked-work-item provider already follows (cap 8 items, 600-char excerpt, real
   `htmlUrl`).
8. **Audience-scoped privacy + a `consistencyToken` + a delivery leak-guard.** Each
   projected fact carries an **audience** (who may see it) and a **`consistencyToken`**
   (a read-time snapshot stamp, à la Zanzibar's "zookie"), so a reviewer-side fact can
   never be delivered into an author-facing surface, and a stale projection can never be
   silently mixed with a fresh one. The delivery path keeps the existing **leak-guard**
   (internal `context/target/*` paths and metadata-only practices are filtered out of
   author-visible diff notes unless explicitly allow-listed).
9. **Fail-CLOSED tenancy (target state).** The Fabric does not relax ADR 0004; it hardens it. Target
   state pairs a **THROW-mode statement inspector** with **Postgres Row-Level Security
   (RLS)** so a projection query that lacks a `workspace_id` predicate *fails the request*
   rather than falling open. (ADR 0004 ships `log` mode today with a `throw`-in-prod
   revisit trigger; the Fabric is a forcing function for that flip plus RLS as
   defence-in-depth.)
10. **`workspace_binding` de-spine.** Workspace-scoped configuration of *which* connectors
    are active and *how* they project becomes a `workspace_binding`-style row rather than
    columns on `Workspace` — the same de-spining ADR 0015 did to credentials, extended to
    projection config.

## Evidence triangulation

The shape above is not invented; three independent industry families converge on it.

- **Connector convergence — Port Ocean / Backstage / Airbyte.** Port's *Ocean*
  integration framework, Spotify's *Backstage* entity model, and *Airbyte*'s source
  connector SPI independently land on the same triad: a typed entity graph, a declarative
  connector that *projects* upstream content into a local model, and capability/manifest
  gating. Three teams solving "ingest many external systems into one model" arriving at
  one shape is strong evidence the `Connector` superset + capability registry is the
  right seam, not an over-fit. (Port Ocean docs; Backstage Software Catalog model;
  Airbyte protocol/connector spec.)
- **Audience + priority — MCP.** Anthropic's **Model Context Protocol** carries an
  explicit `audience` and `priority` annotation on resources/content. We adopt the same:
  a projected fact declares who it is for and how load-bearing it is, which is exactly the
  audience-scoping in decision §8. (MCP specification, resource annotations.)
- **Consistency token — Zanzibar.** Google's **Zanzibar** uses a "zookie" — an opaque
  snapshot token — so a permission check reads at a known-consistent point and a stale
  read cannot silently win. Our `consistencyToken` is the same device for projected
  content. (Zanzibar: Google's Consistent, Global Authorization System, USENIX ATC 2019.)
- **Lazy non-lossy projection — Iceberg.** Apache **Iceberg**'s lazy, non-lossy schema
  evolution (read old data through new schema without rewriting) is the model for
  conformance: project content into the Kind vocabulary lazily and without discarding the
  raw upstream, so a later, richer reader is not blocked by an early, lossy projection.
  (Iceberg table spec / schema evolution.)
- **Why we renamed "bronze" — Databricks medallion.** In the **Databricks medallion**
  architecture the *bronze* layer is the **raw landed source of truth**; silver/gold are
  the derived, cleaned views. Our trust direction is the opposite: **SQL is truth, disk is
  the derived cache.** Reusing "bronze" for our CAS would tell every future reader the
  on-disk hash store is authoritative — the precise inversion we must not ship. Hence
  decision §2 calls it a CAS. (Databricks medallion architecture guidance.)

## Shipped slice

This slice does **not** build the `Connector` SPI, the CAS, the `entity_node`/`entity_edge`
graph, RLS, or `workspace_binding`. It ships the **smallest useful slice of the Fabric on
the seam that already exists** — the `ContentSource` SPI from ADR 0007 — proving the
reframe end-to-end before paying for the migration:

- **Cross-context `ContentSource`s** under `agent.context.providers`, each best-effort
  (`required() == false`: a missing repo/branch/issue logs and skips, never aborts the
  job), each telescope-not-cage (capped, excerpted, every item carries a real `url`):
  - `linked_work_items.json` — already shipped; resolves closing/branch/commit issue refs
    to the issue row with an excerpted body (the acceptance criteria).
  - `branch_graph.json` — `looksBranchedOffFeatureBranch` + `commitsAhead` +
    `distinctAuthorsInRange`, computed from the local clone via
    `GitRepositoryManager.walkCommits` / `GitDiffOperations.resolveDiffRange`.
  - `test_presence.json` — `repoHasTestTarget` from a bounded, contents-free
    `Files.walk` of the clone (path strings only, capped).
- **Consuming practices** that turn those files into formative feedback:
  - `honours-linked-issue-acceptance-criteria` (goal `review-ready-work`) — consumes
    `linked_work_items.json` + `diff.patch`; asks which of `#N`'s criteria are done vs
    deferred; **never asserts an AC is unmet** from code it cannot verify.
  - `branches-from-the-integration-branch` (goal `delivery-and-version-control-discipline`)
    — consumes `branch_graph.json`; nudges the branching habit, MINOR-only, heuristic.
  - `keeps-the-test-suite-honest` — **revised** to read `test_presence.json`: when
    `repoHasTestTarget` is false, a "tests pass" DoD claim is vacuous; a calibrated,
    mostly-MINOR team-wide standing nudge, not a per-MR blocker.
- **Delivery leak-guard wiring** (the integrator edits): the three new files are added to
  `PullRequestReviewHandler.ALLOWED_INTERNAL_CONTEXT_PATHS` and the two new metadata-level
  slugs to `METADATA_LEVEL_PRACTICES`, so an observation grounded in a cross-context file
  survives `filterByDiffScope` instead of being dropped as out-of-diff-scope.

The audience tag, `consistencyToken`, split-confidence edges, RLS, and the
`Connector` superset are **not** in the original slice. They are the staged follow-ups below.

## CAS and final filesystem layout

The on-disk substrate is not SCM-special. The following decisions are **realized**
(additive, no schema change), so the layout is in its final, integration-namespaced shape and a
future connector slots in with no restructuring:

- **§1 SQL-truth / disk-is-cache, §2 CAS (no "bronze").** `FabricLayout`
  (`integration.core.fabric`) is the single source of every cache path under one configurable
  `hephaestus.fabric.root` (defaults to the old `git.storage-path`):
  `bulk/{connectorId}/{externalId}` (the git clone is `bulk/scm/{repoId}`), `cas/{ab}/{rest}`
  (`ContentAddressedStore` — sha-256, two-char fan-out, build-on-miss, atomic writes, striped
  locks, mark-and-sweep GC), `derived/`, and `jobs/{jobId}/` for replay. `GitRepositoryManager`
  routes its clone path through `FabricLayout.bulkArtifact`; a clone is a rebuildable cache so
  the move needs no data migration.
- **Sandbox view = the telescope (§7).** The repo mounts at `/workspace/blobs/scm/repo` (one
  namespace among future `blobs/slack`, `blobs/outline`) with a back-compat `repo → blobs/scm/repo`
  symlink (`SandboxSpec.symlinks` + `SandboxWorkspaceManager.injectSymlinks`), so the agent surface
  and the `repo/` strippers are unchanged. `context/target/manifest.json` (`ContextManifestBuilder`)
  is the integration-agnostic index: one entry per projected file with `{path, connector, bytes,
  sha256}`. Every projected blob is stored in the CAS, so identical context deduplicates across jobs and
  each entry carries a content-addressed provenance hash. (Enforcing it — rejecting an agent citation
  whose sha is absent — is a follow-up; today the sha is recorded, not yet validated.)
- **Toward the `Connector` superset (§3).** `ContentSource` gained `connectorId()` (additive
  default `"scm"`) so each file's producing integration is recorded in the manifest. The full
  `Connector` SPI rename + capability registry remain follow-ups.
- **Cache GC.** `FabricGarbageCollector` (`@Scheduled`, mirrors the existing retention sweepers)
  prunes expired `jobs/` dirs then mark-and-sweeps unreferenced CAS blobs.

**Still open** (unchanged from the staged list): `entity_node`/`entity_edge` + split confidence,
the five-Kind PROV-O vocabulary, audience + `consistencyToken`, fail-CLOSED tenancy (THROW + RLS),
`workspace_binding` de-spine, and the `Connector` SPI rename itself.

## Consequences

Positive:

- The agent stops being context-blind on the highest-payoff context-blind misses, on a
  seam that already exists — zero schema migration, zero new SPI.
- Each provider is best-effort and each practice is NA-safe: a workspace with git
  disabled, no linked issue, or no test target simply gets silence, never a spurious
  observation or a failed job.
- The reframe is validated cheaply: if cross-context providers + consuming practices pay
  off here, the `Connector`/CAS/RLS migration is justified; if not, we have spent one slice,
  not an epic.

Neutral / negative:

- The `ContentSource` seam was not designed as the full `Connector` superset; these
  providers fit it, but the richer fetch/Kind/edge surface is absent and the providers
  encode their own ad-hoc projection. That debt is intentional and named here.
- `test_presence.json` and `branch_graph.json` are **heuristics**. `repoHasTestTarget`
  false can be a false negative for unconventional layouts; `looksBranchedOffFeatureBranch`
  can be a false positive for a long-lived solo branch that merged the integration branch
  back in. Both practices are deliberately capped (MINOR / standing-nudge) to keep the
  cost of a false positive low — the calibration is the mitigation.
- Tenancy is still ADR 0004 `log`-mode shape for these read paths; the fail-CLOSED
  THROW+RLS posture is target state, not shipped. Until then the controller layer remains
  the security boundary for cross-context reads, exactly as ADR 0004 documents.

## Staged follow-ups (the rest of the Fabric)

Additive, in dependency order: (1) `Connector` SPI superset of `ContentSource` with a
capability registry; (2) CAS for the on-disk cache (generalising the git clone); (3)
`entity_node`/`entity_edge` with split match/assertion confidence; (4) the five-Kind
PROV-O vocabulary + lazy non-lossy conformance; (5) audience + `consistencyToken` on every
projected fact; (6) fail-CLOSED tenancy (flip ADR 0004 to THROW + add Postgres RLS); (7)
`workspace_binding` de-spine of projection config. Each lands as its own PR with its own
ADR delta; none blocks the shipped slice.

## Revisit trigger

- A second non-SCM connector (Outline doc, Slack thread) needs to project content — the
  forcing function to promote `ContentSource` to the `Connector` superset (§3) rather
  than adding a fourth ad-hoc provider.
- A cross-context link needs to be *asserted* (not just hinted) into an observation — the
  forcing function for `entity_node`/`entity_edge` with split confidence (§5).
- A projected fact needs to be reviewer-scoped (visible to a maintainer but not the
  author) — the forcing function for the audience tag (§8); until then the leak-guard
  allowlist is the coarse stand-in.
- ADR 0004's counter reads clean for a calendar week and prod flips to `throw` — fold RLS
  in at that point (§9).

## Sources

Port Ocean integration framework docs; Spotify Backstage Software Catalog entity model;
Airbyte connector/protocol specification; Anthropic Model Context Protocol specification
(resource `audience`/`priority` annotations); Zanzibar: Google's Consistent, Global
Authorization System (USENIX ATC 2019, the "zookie" snapshot token); Apache Iceberg table
spec and schema-evolution guidance; Databricks medallion (bronze/silver/gold) architecture
guidance; W3C PROV-O provenance ontology. Internal: ADR 0015 (integration framework),
ADR 0004 (SQL-layer tenancy), ADR 0007 (sandbox/ContentSource seam), ADR 0014 (per-row
AAD), and internal mentor-quality evaluation.

## Update — 2026-08-04 (issue #1430)

The status moves from **Proposed** to **Accepted**: revisit triggers §3 and §8 above have both fired,
and the answer shipped as a versioned artifact-source contract. This update records that contract and
supersedes the parts of the decision register it replaces. Everything above it is left as decided,
including the industry triangulation that produced this shape — the reasoning is the reusable part.

### What changed in the register

- **§3 `Connector` SPI (target state) → superseded.** The forcing function the revisit trigger
  predicted (a second non-SCM connector) arrived. What shipped is not a `Connector` interface rename
  but an **evidence-source contract**: every input that may support a practice-review judgment has a
  logical *source kind* in a versioned artifact-source catalog. The catalog defines authority,
  capture time, freshness, completeness, privacy class, supported absence states, purpose, retention,
  erasure, and its governance decision. Evidence profiles close the set of source kinds available to
  each reviewed artifact type. Contract tests reject any uncatalogued `EvidenceSource` provider.
- **§4 five PROV-O Kinds → not adopted in that form.** The typed vocabulary is the source-kind
  catalog, not a five-Kind entity taxonomy. PROV-DM remains the reference for explicit
  entity/activity/provenance; the closed-vocabulary role went to the catalog.
- **§5 `entity_node`/`entity_edge` with split confidence → still open.** No node/edge graph shipped.
  Citations bind an observation to a declared, available source and exact artifact instead.
- **§7 telescope manifest → holds, and hardened.** The sandbox sees only materialized files beneath
  `/workspace/inputs`; handlers add no host mounts. A repository tree, when explicitly selected by an
  evidence plan, is copied into `inputs/sources/scm/repo/` and inventoried like any other source. The
  model-visible manifest carries the *selected* artifact index; the complete source manifest and the
  readiness report are persisted in the job evidence snapshot for audit after replay-cache collection.
- **§8 audience-scoped privacy → shipped as purpose-scoped grants.** See "Governance" below. The
  audience tag became a `source:purpose` grant, which is the narrower and more auditable form.
- **§1, §2 SQL-truth and CAS → unchanged, layout finalised** (see "Filesystem layout" below).
- **§6, §9, §10 (capability registry, fail-CLOSED tenancy, `workspace_binding` de-spine) → still
  open**, unchanged from the staged list.

### Readiness replaces silent absence

A practice revision declares required and optional source kinds. The runtime records a full source
manifest and a per-automated-review readiness report. If required evidence is unavailable, stale,
incomplete, redacted or denied, that practice is marked **not ready** with typed evidence reasons. If
no practice remains ready, the job completes with `INSUFFICIENT_EVIDENCE` **without starting the
agent** — it never manufactures a semantic `NOT_APPLICABLE` result. This is the substantive
consequence of the ADR's original complaint that "an empty result is ambiguous": empty, missing,
stale, partial, redacted, failed and ablated inputs are now distinguishable from one another.

The contract applies to practice reviews. Mentor conversation context is governed by the mentor
consent and integration contracts and cannot be cited as practice-review evidence; reusing a mentor
input for a practice review means first making it a catalogued source kind.

### Filesystem layout (finalises §1/§2)

`FabricLayout` owns the cache root:

```text
sources/scm/{repositoryId}/     local integration cache
cas/sha256/{ab}/{rest}          immutable content-addressed blobs
jobs/{jobId}/                   bounded replay manifest and readiness report
```

CAS writes and collection use cross-process prefix locks. Collection removes only blobs that are
unreferenced *and* older than the retention cutoff; reusing a blob refreshes its retention age before
a new manifest is published; an unreadable manifest stops the sweep rather than risking deletion of
live evidence.

### Governance and minimization (supersedes §8's audience tag)

A checked-in, unexpired decision is what authorizes a source for one purpose, and it is recorded per
`source:purpose` pair rather than per source. Capture, feedback delivery, mentoring reuse and
operator evidence review each check their own decision, so withdrawing one stops that use without
widening or rewriting the others. A source whose decision is missing, refused or expired is never
read, and Hephaestus skips the practice rather than collecting the data.

A per-deployment override of those decisions was considered and is not part of this decision: a
deployment that could widen a grant would make the shipped contract unfalsifiable, and one that could
narrow it would silently disable practices the workspace had switched on. Changing what may be read
is therefore a contract change, reviewed and versioned.

Adding or widening a source requires an updated source descriptor, profile inventory, governance
decision, retention/erasure coverage and contract tests. **Accuracy benefit alone is not
authorization.** Published contract versions are immutable; renewal or semantic change creates a new
version.

### Provenance and delivery

Artifacts are addressed by SHA-256, and a citation binds an observation to a declared, available source
and an exact artifact; diff citations additionally bind file, side and line range. Deterministic secret
scanning validates a transient line digest but removes that digest before persistence, retaining only
redacted location evidence. This proves source *attribution*, not causal isolation inside a shared
model invocation — the runtime still rejects any observation that cites a source outside its practice
declaration.

### Added consequences

- Retained job records preserve the exact contract, manifest and readiness outcome for authorized
  operator audits. This ADR does not add a dedicated audit UI.
- Source-contract changes require explicit version migration and mark dependent claims stale.
- The CAS and replay cache have bounded residual windows; immediate selective erasure would require
  reference-aware collection and remains a deployment approval consideration.
- `branches-from-the-integration-branch`, listed among the consuming practices in § Shipped slice
  above, is withdrawn from the catalog. The other two remain.

### Added evidence

The original triangulation (Port Ocean / Backstage / Airbyte, MCP, Zanzibar, Iceberg, Databricks)
stands. The contract layer adds: [W3C PROV-DM](https://www.w3.org/TR/prov-dm/) for explicit entities,
activities and provenance; [SLSA provenance v1.2](https://slsa.dev/spec/v1.2/provenance) for immutable
subject identity; [JSON Schema 2020-12](https://json-schema.org/draft/2020-12/json-schema-core) for
closed machine contracts; and [GDPR Article 5](https://eur-lex.europa.eu/eli/reg/2016/679/art_5/oj)
and [Article 25](https://eur-lex.europa.eu/eli/reg/2016/679/art_25/oj) for minimization, storage
limitation and protection by default.

### Revisit trigger (amends the list above)

Triggers §3 and §8 have fired and are answered. Still live: a cross-context link that needs to be
*asserted* rather than hinted (`entity_node`/`entity_edge`, §5), and ADR 0004's counter reading clean
for a calendar week so prod can flip to `throw` and fold RLS in (§9).

## Update — 2026-08-30 (issue #1636)

[ADR 0039](0039-git-and-postgresql-own-evidence.md) supersedes §1/§2's realised filesystem CAS and
the “Filesystem layout” subsection of the 2026-08-04 update. The statement that SQL is authoritative
still holds for manifests, references, captured API payloads, liveness, retention and erasure. Its
application to repository bytes is now more precise: Git owns the commit/tree/blob object closure
pinned by a live job keep-ref, while PostgreSQL owns the root set. Temporary worktrees and payload
materializations remain disposable projections.

There is no filesystem content-addressed evidence store in the 1.0 target. ADR 0039 supersedes the
`cas/sha256/**` and `jobs/**` replay layout, manifest-walking garbage collection, independent CAS
retention clock, and shared-fabric deployment contract above.

The 2026-08-04 update's "bounded residual windows" consequence for the CAS and replay cache remains
accurate for pre-cutover releases only; in the 1.0 target ADR 0039's SQL-root-set collection replaces
it, and selective erasure stops being a deployment-approval consideration tied to a filesystem cache.

## Update — 2026-09-03 (issue #1719)

[ADR 0041](0041-compose-1x-kubernetes-2.md) supersedes what remains of § CAS and final filesystem
layout, the 2026-08-04 update's § Filesystem layout (finalises §1/§2), and § Decision register's
retained-payload entries. One job folder per attempt, rendered at job start, replaces them.
