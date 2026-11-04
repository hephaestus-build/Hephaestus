# ADR 0077: Provider mirrors are shared, and the monitored scope isolates them

**Status:** Accepted
**Date:** 2026-11-04
**Authors:** Felix T.J. Dietrich
**Amends:** [ADR 0004](0004-sql-layer-tenancy-via-statement-inspector.md) (SQL-layer tenancy).
**Relates to:** [ADR 0076](0076-reads-are-shaped-like-the-screen.md) (reads), [Workspace context](../contributor/workspace-context.mdx), [Database migration § Workspace column](../contributor/database-migration.mdx#workspace-column).

## Context

Every workspace-owned table carries a NOT NULL `workspace_id`.
`WorkspaceTableColumnIntegrationTest` checks this rule.
23 tables keep the provider mirror: the repositories, work, comments, reviews, commits, labels, milestones and teams that sync reads from GitHub and GitLab.
They have no `workspace_id`.

A mirror row is one provider object.
It is unique per provider (`uq_repository_provider_native_id`), and more than one workspace can monitor the same repository.
Thus, one NOT NULL `workspace_id` on a mirror row would need a copy of the row for each workspace that monitors it.

`WorkspaceStatementInspector` (ADR 0004) accepts a statement on a scoped table when the word `workspace_id` occurs anywhere in it.
For a mirror table, the word can only come from another table.
A column-to-column join (`c.workspace_id = rtm.workspace_id`), a negation (`workspace_id <> ?`), a comment or a string literal satisfies the check and binds no workspace.

ADR 0004 also tells a repository that scopes through a foreign-key chain to carry `@WorkspaceAgnostic` on its type.
On 2026-11-04, 229 types carried the marker, and 152 of them were not repositories.
`WorkspaceAgnosticAspect` opens the bypass for the whole call, so every statement that runs below a marked type is not checked.
19 repositories that read mirror tables carried the marker on their type.

## Decision

1. **The mirror is shared.** The tables in `WorkspaceScopedTables.MIRROR_TABLES` keep one row per provider object, shared by every workspace that monitors it. They get no `workspace_id`.
2. **The monitored scope is the only path from a workspace to a mirror row.** Repository data is reached through `repository_to_monitor`. Organization data is reached through `workspace.organization_id`.
3. **A read of a mirror table binds the workspace on that path.** The inspector accepts a statement that reads a mirror table only if it has a positive bound predicate: `workspace_id` equal to a parameter, in parameters or equal to `ANY` of a parameter, or the `id` of the `workspace` table equal to a parameter. The predicate must not be in a comment or a string literal, and must not be under `NOT`, `NOT EXISTS` or `NOT IN`. A primary-key load keeps the fast path from ADR 0004, because every lazy association is one.
4. **An id that a request supplies for a mirror object resolves through the monitored scope before any load.** A `@WorkspaceScopedController` path turns such an id into a row only through a monitored-scope method, such as `RepositoryRepository.isMonitored` or `findMonitoredRepositoryIds`. An ArchUnit rule will enforce this.
5. **A read that crosses workspaces on purpose carries `@WorkspaceAgnostic("<reason>")` on its method.** Examples are sync, erasure and the check that a shared repository is still monitored before it is deleted. The reason names the caller that needs every workspace. This decision replaces the type-level marker that ADR 0004 § Consequences prescribes for foreign-key chains.

## Limits

The guard does **not** cover mirror reads in general. Read these limits with the decision.

- **A type-level marker turns the guard off for its whole call.** The guard checks a mirror read only on a path that no type-level `@WorkspaceAgnostic` encloses. On 2026-11-04 that excluded most of the service layer: 229 type-level markers, 152 of them on types that are not repositories. Decision 5 removes them type by type. Until then, decisions 2 to 4 are a rule for authors and reviewers, and the inspector enforces it only where no marker encloses the call.
- **A primary-key load passes.** The id that it loads must come from a scoped read. Decision 4 covers ids from a request. Review covers the rest.
- **A bound predicate on an unrelated table passes.** Text cannot show which alias a predicate constrains. An example is `FROM issue i, membership m WHERE m.workspace_id = ?`. Review and the cross-workspace integration tests cover it.
- **A disjunction can widen a bound predicate.** `WHERE rtm.workspace_id = ? OR …` passes. ADR 0004 records the same limit for its fast paths.

## Consequences

- The integration tier runs the inspector in `throw` mode. Thus, a mirror read that only names the workspace fails there.
- `WorkspaceTableColumnIntegrationTest` reads `MIRROR_TABLES` and does not list the mirror again.
- Converting the type-level markers is a series of small changes, one type at a time. Each change is measured with the integration tier in `throw` mode.

## Revisit trigger

A mirror table needs data that differs per workspace. Or the type-level markers are gone, and the inspector can check every mirror read.
