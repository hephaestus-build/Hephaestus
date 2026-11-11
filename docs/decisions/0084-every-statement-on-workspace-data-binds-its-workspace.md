# ADR 0084: Every statement on workspace data binds its workspace, and a shared mirror binds it through the monitored scope

**Status:** Accepted
**Date:** 2026-11-10
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0004](0004-sql-layer-tenancy-via-statement-inspector.md), [ADR 0077](0077-provider-mirrors-are-shared-and-the-monitored-scope-isolates-them.md)

**Relates to:** [ADR 0020](0020-context-fabric-everything-is-an-integration.md): decision 9 names RLS as a target state. This ADR records the RLS position.

## Context

Two ADRs and five updates described workspace tenancy.
To learn what the guard checks at 1.0, a reader had to replay ADR 0004, its four updates, ADR 0077 and its update.
Some of that text no longer held. A named test never existed, and a limit was stated too narrowly.
This ADR states tenancy at 1.0 as rules.
[Workspace-scoped endpoints](../contributor/workspace-context.mdx) keeps the design, and [Database migration § Workspace column](../contributor/database-migration.mdx#workspace-column) keeps the schema rule.

Workspace authorization at the controller is the primary boundary.
The SQL check is the second line. It catches a statement that forgets its workspace.
Provider mirror rows are shared, so they cannot carry one workspace.

## Decision drivers

- A query that forgets its workspace fails in the test tier, not in production.
- One list says which tables are global, and adding to it is a reviewed security decision.
- A read that crosses workspaces names its reason on the method that does it.
- A mirror row exists once, however many workspaces monitor its repository.

## Considered options

1. **A Hibernate `StatementInspector` with method-level exemptions (chosen).** It sees every statement that Hibernate runs, native queries included. It reads SQL text with regular expressions, so it checks shape, not values.
2. **Hibernate `@TenantId` (discriminator multitenancy).** Each partitioned entity must hold one tenant id. A mirror row belongs to every workspace that monitors it, so it has no single tenant id.
3. **PostgreSQL row-level security (RLS).** It checks values at run time and denies by default. It is not the simpler way for us. The costs are listed under § What changed and why.

## Decision

1. **Every workspace-owned table carries a NOT NULL `workspace_id`** from its first migration. A table that belongs to no workspace is in `WorkspaceScopedTables.GLOBAL_TABLES` under a comment that gives the reason. Adding a table there is a security decision. A mixed-scope table has a nullable column, a recorded reason, and stays under the inspector.
   Enforced by: `WorkspaceTableColumnIntegrationTest#shouldCarryNotNullWorkspaceIdInEveryWorkspaceTable`, `WorkspaceScopedTablesParityTest#productionAndArchAllowlistsMatch` and `DataIsolationArchitectureTest#entitiesHaveWorkspaceRelationshipPath`.
2. **The provider mirror is shared.** The tables in `WorkspaceScopedTables.MIRROR_TABLES` keep one row for each provider object and no `workspace_id`. A workspace reaches a mirror row only through `repository_to_monitor` or `workspace.organization_id`.
   Enforced by: `WorkspaceTableColumnIntegrationTest#shouldCarryNotNullWorkspaceIdInEveryWorkspaceTable`, which also keeps each mirror table under the inspector.
3. **The inspector checks every statement that Hibernate runs, and refuses a violation.** `throw` is the only mode. The `log` and `off` modes of `hephaestus.tenancy.enforcement` keep a second path alive that production never uses, so they go.
   Decided, not shipped: on main, `throw` is the default and `log` and `off` still exist. No pull request deletes them yet.
   Each violation counts in `tenancy.violation.total{table, mode}` and fires `HephaestusTenancyViolations`.
   Enforced by: `TenancyEnforcementPropertiesTest#defaultsToThrow`, and the promtool cases in `hephaestus-alerts.test.yml` that `scripts/check-alert-rules.ts` requires.
4. **A statement on workspace data binds its workspace.** On a workspace-owned table, the inspector checks only that the statement names `workspace_id`. On a mirror table, it requires a bound predicate. That predicate is `workspace_id` equal to, in, or `= ANY` of a parameter, or `workspace.id = ?`. It is not under `NOT`, in a comment or in a string literal.
   Enforced by: the mirror cases of `WorkspaceStatementInspectorTest`, and `RepositoryMethodsTenancyIntegrationTest#shouldAcceptEveryDeclaredMethodWhenNoBypassIsOpen`.
5. **A load or a write by a key that the caller already holds passes.** This covers a key equality or a list of bound keys, and a complete composite key. It also covers a join-table delete by both keys. An `OR` disqualifies each form. An INSERT passes.
   Enforced by: `WorkspaceStatementInspectorTest#hibernateEntityLoadByPkIsAllowed`, `#shouldAcceptALoadByTheCompleteCompositeKey`, `#pkPredicateDisjoinedWithOrStillEnforced`, and `TenancyCompositeKeyIntegrationTest`.
6. **An id that a request supplies for a mirror object resolves through the monitored scope before any load.** An example of such a method is `RepositoryRepository.isMonitored`.
   Not enforced. Review catches it. No pull request adds the ArchUnit rule that ADR 0077 promised.
7. **A read that crosses workspaces on purpose carries `@WorkspaceAgnostic("<reason>")` on its method.** The reason names the caller that needs every workspace. A marker on a type opens no bypass.
   Enforced by: `WorkspaceAgnosticAspectTest#shouldOpenNoBypassForAMarkerOnTheType` and `#shouldOpenTheBypassOnlyWhileAMarkedMethodRuns`. The compiler requires the reason.
8. **SQL lives in repository methods.** Spring JDBC is allowed only in `core.database`, in configuration and at the person-data boundary.
   Enforced by: `CodeQualityTest#rawSqlLivesInRepositories`.
9. **Every workspace endpoint is a `@WorkspaceScopedController`.** Its filter resolves the slug before the controller runs. For a signed-in account, an unknown slug and a non-member get the same 404. Thus nobody can probe which workspaces exist. An object of another workspace answers 404.
   Enforced by: `WorkspaceScopedControllerIntegrationTest`, `CrossTenantIsolationIntegrationTest#shouldAnswerNotFoundWhenNonMemberCallsScopedEndpoint` and `CrossTenantIsolationIntegrationTest` for each domain. `MultiTenancyArchitectureTest#dataEndpointsReceiveWorkspaceContext` checks the annotation by name only. No test calls every endpoint across workspaces.
10. **The inspector never fails a statement by its own error.** A failed analysis lets the statement pass and counts `tenancy.parse_failure.total`.
    Enforced by: `WorkspaceStatementInspectorTest#backslashEscapedPostgresCastDoesNotPropagate`. No test asserts the counter.

### Limits

The inspector checks the shape of the text. These shapes pass, and authorization and review cover them:

- **A single key equality passes before the workspace check.** That is `<alias>.<x>_id = ?` in a SELECT, or `<x>_id = ?` in an UPDATE or DELETE. This holds on workspace-owned and mirror tables alike. A derived finder by any foreign key thus passes. No pull request narrows it to the mapped key.
- A key-anchored load does not check the other tables that it joins.
- On a workspace-owned table, `workspace_id` anywhere passes, also in `<>`, a comment or a literal.
- A bound predicate on an unrelated table passes, and a disjunction can widen a bound predicate.
- Only the first table of a comma-separated FROM list is read.
- The inspector does not see a statement outside Hibernate (rule 8).

## What changed, and why

- **`@TenantId` is dropped, not deferred.** ADR 0004 filed it as a follow-up. Hibernate's discriminator approach needs a tenant id on each partitioned entity, which it fills on persist. A shared mirror row has no single workspace (rule 2), so the follow-up cannot work.
- **RLS is not used at 1.0.** It is defense in depth, not a replacement for rule 3. `SET LOCAL` has effect only inside a transaction. A session-level setting persists on a pooled connection after the request. A policy on a mirror table would need a subquery on the monitored scope for each row. Each method-level exemption needs its own bypass. Foreign-key checks bypass RLS. The application role already has no `BYPASSRLS` (`DatabaseRolesDatabaseTest#shouldHoldTheApplicationRoleToRowLevelSecurityWhenATableEnablesIt`). This keeps a later move cheap.
- **`CrossWorkspaceIsolationTest` never existed.** ADR 0004 named it as the controller-layer counterpart. Rule 9 names the tests that exist.
- **The key limit is wider than ADR 0077 said.** ADR 0077 wrote "a primary-key load passes". Any single `<x>_id = ?` passes, because the key fast path runs before the mirror check.
- **Rule 6 has no ArchUnit rule.** ADR 0077 promised one. It is now `Not enforced.`
- Rules 3, 5 and 7 fold in the four updates of ADR 0004 and the update of ADR 0077.

## Consequences

- Good: a statement that forgets its workspace fails in the integration tier. That tier runs as `hephaestus_app`, the production database role.
- Good: a mirror row is stored and synced once, however many workspaces monitor it.
- Good: each read that crosses workspaces names its reason where it happens, and review sees each new one.
- Bad: the inspector checks text. The limits above pass, so authorization stays the boundary that must hold.
- Bad: a mirror read must join the monitored scope, which adds a join to each read.
- Bad: nothing at the database refuses a cross-workspace row. A statement that the inspector does not see is checked by review only.

## Confirmation

Each rule above names its test or gate.
`DataIsolationArchitectureTest` checks entity mapping and allowlists. It does not check SQL. The runtime guard is the inspector with `RepositoryMethodsTenancyIntegrationTest`.
Rule 6 and the endpoint sweep of rule 9 are not enforced. Code review catches them.

## Revisit trigger

- A mirror table needs data that differs for each workspace.
- A cross-workspace leak ships through one of the limits above.
- `tenancy.parse_failure.total` is not zero in production.
- Spring JDBC spreads past the places that rule 8 allows. Then price RLS on the NOT NULL `workspace_id` tables again.

## Sources

- PostgreSQL 18, [Row Security Policies](https://www.postgresql.org/docs/18/ddl-rowsecurity.html) and [SET](https://www.postgresql.org/docs/18/sql-set.html). "If no policy exists for the table, a default-deny policy is used." Owners "normally bypass row security". Foreign-key checks "always bypass row security". `SET LOCAL` outside a transaction "has no effect". These are the costs of option 3.
- Hibernate ORM 7.0, [Multitenancy](https://raw.githubusercontent.com/hibernate/hibernate-orm/7.0/documentation/src/main/asciidoc/userguide/chapters/multitenancy/MultiTenancy.adoc) and [`@TenantId`](https://docs.hibernate.org/orm/7.0/javadocs/org/hibernate/annotations/TenantId.html). "Each entity representing partitioned data must declare a field annotated `@TenantId`." This is why option 2 cannot hold a shared mirror.
- OWASP, [Multi-Tenant Application Security Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Multi_Tenant_Security_Cheat_Sheet.html). "Treat client-supplied tenant identifiers as selectors only" supports rule 9. "Never assume a newly borrowed connection has the correct setting" is the pooling cost of RLS.

## Already priced and declined

- [ADR 0004](0004-sql-layer-tenancy-via-statement-inspector.md#considered-options): native `@TenantId` after a denormalization epic.
- [ADR 0004](0004-sql-layer-tenancy-via-statement-inspector.md#considered-options): a `@TenantId` and inspector hybrid.
- [ADR 0004](0004-sql-layer-tenancy-via-statement-inspector.md#considered-options): Hibernate `@Filter` and `@FilterDef`.
- [ADR 0004](0004-sql-layer-tenancy-via-statement-inspector.md#decision): JSqlParser, which activates Spring Data's query enhancer and breaks boot.
- [ADR 0004](0004-sql-layer-tenancy-via-statement-inspector.md#consequences): `log` as the production mode.
- [ADR 0004](0004-sql-layer-tenancy-via-statement-inspector.md#consequences) and [ADR 0077](0077-provider-mirrors-are-shared-and-the-monitored-scope-isolates-them.md#update--2026-11-05-a-type-level-marker-opens-no-bypass): `@WorkspaceAgnostic` on a type as a bypass.
- [ADR 0077](0077-provider-mirrors-are-shared-and-the-monitored-scope-isolates-them.md#context): a copy of each mirror row for each workspace that monitors it.
- [ADR 0077](0077-provider-mirrors-are-shared-and-the-monitored-scope-isolates-them.md#context): any mention of `workspace_id` as proof for a mirror read.
