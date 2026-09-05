# ADR 0004: SQL-layer tenancy enforcement via WorkspaceStatementInspector

**Status:** Accepted
**Date:** 2026-05-20
**Authors:** Server foundations epic (#1097)

## Context

Cross-workspace data leakage is a security risk every Hephaestus deployment must
avoid. ArchUnit tests catch some categories at the repository layer, but nothing
catches a query that emits SQL against a workspace-scoped table without a
`workspace_id` predicate. The bug class is: someone writes a `@Query("SELECT ...
FROM pull_request")` and forgets the workspace filter — the test suite passes,
the leak ships.

Hibernate 6 ships native multi-tenancy support via `@TenantId` +
`CurrentTenantIdentifierResolver`. That's the modern discriminator-based approach,
auto-injects the predicate, and auto-fills the column on insert. But:

- It does NOT cover native queries, `@Modifying` deletes, or some projection shapes
- Multi-hop entities (CommitFileChange → Commit → Repository → Organization ←
  Workspace) need `workspace_id` denormalization onto every entity for `@TenantId`
  to attach the predicate cheaply — that's a 6–12 week migration epic of its own

A Hibernate `StatementInspector` works at the SQL-emit boundary (catches
everything, including native queries) but needs a runtime escape hatch for
legitimately-cross-workspace queries (workspace listing, slug history, admin
maintenance).

## Decision drivers

- Catch the leak class at PR time, not in production
- Don't block on a 6–12 week schema migration (`@TenantId` denormalization)
- Make `@WorkspaceAgnostic` annotation load-bearing (not just docs)
- Performance: hot-path SQL must stay fast (regex + cache)
- Single source of truth for which tables are scoped
- The inspector must NEVER throw — fail-open with observability for pathological
  input

## Considered options

1. **`StatementInspector` + AOP bypass + JPA-metamodel SSOT (regex-only
   pipeline)** — pragmatic; ships without schema migration; defense-in-depth.
2. **`@TenantId` native multi-tenancy** — modern, integrated; needs
   denormalization first.
3. **`@TenantId` + `StatementInspector` hybrid** — best long-term, but option 1 is
   the prerequisite (inspector exists; `@TenantId` adoption layers on later).
4. **Hibernate `@Filter`/`@FilterDef`** — legacy; per-repository activation;
   doesn't catch native queries; effectively superseded by `@TenantId`.

## Decision

Option 1 for this epic. Statement inspector + AOP-driven bypass on
`@WorkspaceAgnostic` + JPA-metamodel-derived `WorkspaceScopedTables` SSOT.

**Regex-only pipeline** (no SQL parser dependency):

1. ThreadLocal bypass check (`@WorkspaceAgnostic` aspect open)
2. Enforcement mode `OFF` short-circuit
3. Caffeine cache lookup (10k entries, LRU)
4. INSERT short-circuit (the value list carries `workspace_id` by construction)
5. PK-anchored DML short-circuit (`WHERE id = ?` with optional optimistic-lock
   `AND version = ?`)
6. PK-anchored SELECT short-circuit (`FROM table alias … WHERE alias.<id|*_id> = ?`)
7. `workspace_id` word-boundary fast path
8. Table-extract fallback — pull table identifiers after `FROM/JOIN/UPDATE/INTO`,
   intersect with `WorkspaceScopedTables.scopedTables()`. Any match without a
   `workspace_id` reference is a violation.

Enforcement mode is configurable (`hephaestus.tenancy.enforcement = throw | log |
off`); `throw` in test, `log` (with Micrometer counter
`tenancy.violation.total{table, mode}`) elsewhere.

**Why regex, not JSqlParser:** JSqlParser was tried and rejected. Adding it to the
classpath caused Spring Data JPA to auto-activate its `JSqlParserQueryEnhancer`,
which fails on legitimate Postgres-escaped `@Query` natives (e.g.,
`CONCAT(:id\:\:text, ...)`) and breaks application boot. A regex-only inspector
has no transitive blast radius and is sufficient for the predicate-shape check
we actually care about.

Native `@TenantId` adoption is filed as a follow-up epic that lands after the
multi-hop denormalization migration.

## Known trade-offs (deliberate, defense-in-depth caveats)

The inspector is **shape enforcement, not authorization**. The controller layer
(workspace context filter + `@PreAuthorize` + URL slug → workspace resolution)
remains the security boundary. Documented trade-offs:

- **PK-anchored SELECT (`WHERE alias.*_id = ?`) admits FK-only predicates.**
  Lazy `@OneToMany` / `@ManyToMany` collection loads emit
  `SELECT * FROM child WHERE parent_id = ?`. Safe by construction *because the
  caller obtained `parent_id` via a workspace-scoped path* — a user-supplied URL
  ID would have to bypass controller-level workspace resolution first. The
  surrogate key is opaque to inputs; the upstream find is the boundary.
- **`workspace_id` word-boundary fast path is mention-anywhere.** A query of
  shape `SELECT … FROM pull_request pr JOIN issue i ON … WHERE i.workspace_id <> ?`
  would pass — the regex sees the token, not the binding direction. Hibernate
  doesn't emit such shapes from JPQL/Criteria; a developer writing this in a
  `@Query` native is explicitly bypassing tenancy and the code review is the
  catch.
- **SQL comments / string literals containing `workspace_id` falsely pass.**
  Same regex limitation. Acceptable because Hibernate-emitted SQL is
  parameterized; user input does not appear inline.
- **Leading CTE (`WITH … AS (…) SELECT …`) breaks the PK-anchored carve-outs.**
  Falls through to the standard `workspace_id` check; legitimate CTE queries on
  scoped tables need `@WorkspaceAgnostic` (Hibernate rarely emits CTEs against
  the dialects we use).

The CrossWorkspaceIsolationTest that asserts 403 on cross-workspace HTTP access
for every `@WorkspaceScopedController` is the controller-layer counterpart and
is scheduled as a follow-up epic (cut from #1097 because the MockMvc fixture
work deserves its own PR).

## Consequences

- Test profile fails loudly on any workspace-scoped query that lacks the predicate.
- Production runs in `log` mode for the staging canary period; flip to `throw`
  after a calendar week of clean counter readings (tracked separately).
- The `@WorkspaceAgnostic` annotation now has runtime semantics: dropping it from
  a repository that issues cross-workspace queries causes
  `TenancyViolationException` in test. Annotation is no longer documentation-only.
- The inspector itself must never throw — pathological input increments
  `tenancy.parse_failure.total` and falls open. Observable rather than
  request-failing.
- `WorkspaceScopedTables` derives the table set from the JPA metamodel at startup
  — new scoped entities get auto-protected; explicit `GLOBAL_TABLES` allowlist
  holds the 11 known exceptions with per-entry rationale.
- Repositories that scope through FK chains rather than a direct `workspace_id`
  column carry `@WorkspaceAgnostic` with a one-line rationale on the type — the
  parent's `workspace_id` predicate catches the same defect class one hop up.

## Revisit trigger

`@TenantId` denormalization epic completes; or the Micrometer counter shows zero
violations for a calendar week and we flip to `throw` in prod; or the regex fast
path produces a false-negative that ships a real leak (would mean the trade-offs
above are not actually defense-in-depth and we need stronger SQL parsing or
controller-layer enforcement only); or
`tenancy.parse_failure.total` starts non-zero in prod (would mean the regex hits a
pathological input class worth handling explicitly).

## Update — 2026-08-30 ([issue #1603](https://github.com/hephaestus-build/Hephaestus/issues/1603))

`throw` is now the default in every profile. The canary period described above has ended; `log` and
`off` remain explicit diagnostic overrides and are not production settings. This changes only the
default response to a violation the inspector already detects. The documented parser carve-outs and
the requirement for workspace authorization remain unchanged.
