# ADR 0076: Reads are shaped like the screen, entities are for writes

**Status:** Accepted
**Date:** 2026-11-03
**Authors:** Felix T.J. Dietrich
**Relates to:** [Read and write the database](../contributor/read-and-write-the-database.mdx) (the how-to), ADR 0075 (transaction boundaries and read-only transactions), [ADR 0072](0072-few-dependencies-owned-slivers-kept-foundations.md) (dependencies).

## Context

The server reads PostgreSQL through Spring Data JPA 4.1.2 on Hibernate ORM 7.4.13, with `open-in-view` off.
On 2026-11-03 the 145 repositories had about 1,250 methods (counted from source):

| Return shape | Derived | JPQL `@Query` | Native `@Query` |
|---|---|---|---|
| Entity | 224 | 178 | 38 |
| Interface projection | 1 | 28 | 96 |
| Record | 3 | 22 | 27 |
| Scalar | 88 | 80 | 161 |
| Modifying | — | 104 | 145 |

Thus, most reads load managed entities, and services map them to DTOs in Java.
The mapping walks lazy associations, so each touched association can cost one more statement per row.
Some DTO factories query the database themselves: `WorkspaceDTO.from` reads the connection table about 10 times.
Two mappings are eager: `Connection.workspace` (an implicit eager `@ManyToOne`) and `Workspace.repositoriesToMonitor` (`FetchType.EAGER`).
Thus, each connection read in a new persistence context costs three statements.

The table statistics on staging (`pg_stat_user_tables` and `pg_stat_user_indexes`, cumulative, measured 2026-11-03) show the cost:

- The 9-row `connection` table was read 27.7 million times, `identity_provider` 14.6 million, `workspace` 7.4 million and `repository_to_monitor` 7 million times. These are the same few rows, read again on each request and each event.
- The index `pull_request_review_pkey` returned 3.56 trillion tuples in 86 million scans, about all of the 3.59 trillion tuples that the database returned. `ActivityLedgerReconciler` caused it: it scans a whole id range after each repository sync.
- The default Activity view took more than 120 s in one statement. A per-row subquery read all of `identity`, because the query left out `disabled_at IS NULL`, the predicate of the partial unique index. A common table expression brought it to 356 ms (measured by the PERF lane).

The last two costs are not statement counts. One statement was slow because PostgreSQL could not use an index.
A statement budget alone does not find that.

## Decision drivers

- **Correctness and type safety.** A query that does not match its result type fails at startup or at compile time, not on a user's screen.
- **What the database reads.** A read selects the columns that the screen shows, in one statement or a few, through an index.
- **One obvious way.** A contributor or an agent copies one pattern. The error that a mistake produces names the fix.
- **Maintenance.** The result type is next to its query. No mapper library, and no field list in two places.
- **Fit.** Spring Data repositories stay the one repository style. A second style must buy a large, measured gain.

## Considered options

### Read pattern

- **Load entities and map them in Java (today's default).** Rejected. Each lazy association that the mapping touches is a statement per row. Every loaded entity enters the persistence context. A read-only transaction removes the dirty check, but not the identity map or the proxies ([Hibernate 7.4 Introduction § 5.10](https://docs.hibernate.org/orm/7.4/introduction/html_single/Hibernate_Introduction.html)).
- **Interface projections.** Rejected as the default. A closed projection selects only its columns through a `Tuple` query. An open projection (`@Value`) or a nested projection loads the entity ([Spring Data JPA 4.1.2, Projections](https://docs.spring.io/spring-data/jpa/reference/repositories/projections.html)). The result is a proxy with no value `equals`. On a native query, a getter with no matching column returns `null` silently.
- **Records from Spring Data `@Query` methods with an explicit `select new` (chosen).** Spring Data passes each JPQL `@Query` to Hibernate's `createQuery` when it creates the repository (`SimpleJpaQuery.validateQuery`, spring-data-jpa 4.1.2 source). Hibernate 7.4.13 resolves the constructor of a `select new` there, before any execution. A wrong number or type of arguments fails with `Missing constructor for type`, and a wrong path with `UnknownPathException` (measured in the integration context, 2026-11-03). Thus, a query that does not match its record fails when the application context starts, in every integration test. A native query maps to a record by position ([Projections § Native Queries](https://docs.spring.io/spring-data/jpa/reference/repositories/projections.html)).
- **Records through Spring Data's DTO rewrite.** Rejected. Since Spring Data JPA 3.5, a select list that returns a record is rewritten to a constructor expression ([Projections § DTO Projection JPQL Query Rewriting](https://docs.spring.io/spring-data/jpa/reference/repositories/projections.html)). The rewrite runs only when the query executes (`AbstractStringBasedJpaQuery.createJpaQuery`), so the startup check does not see the constructor.
- **Hibernate Data Repositories (Jakarta Data 1.0).** Not now. Hibernate Processor type-checks `@Query`, `@HQL` and `@Find` at compile time, and the repository runs on a `StatelessSession` ([Hibernate Data Repositories 7.4](https://docs.hibernate.org/orm/7.4/repositories/html_single/Hibernate_Data_Repositories.html)). That is the strongest check available. But it is a second repository style next to 145 Spring Data repositories. Spring Data does not integrate it, and Spring Framework 7 documents the shared `StatelessSession` for `HibernateTransactionManager`, not the `JpaTransactionManager` that Boot configures. The gain over startup validation is earlier feedback, not different feedback.
- **`JdbcClient` into records for all reads.** Rejected as the default. It maps columns to record components by name ([Spring Framework 7.0 `JdbcClient`](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html#jdbc-JdbcClient)), but nothing checks the SQL before it runs. It also is a second style. The privacy and migration code that already uses it stays.
- **jOOQ or Querydsl.** Rejected. A new framework and a code generation step, against [ADR 0072](0072-few-dependencies-owned-slivers-kept-foundations.md), for a gain that records and startup validation mostly give.

### Guard

- **Hibernate `Statistics`.** Rejected. The counters are global to the `SessionFactory`, so background work in the test context adds to them. They also do not see `JdbcClient` statements ([Hibernate 7.4 User Guide § Statistics](https://docs.hibernate.org/orm/7.4/userguide/html_single/Hibernate_User_Guide.html)).
- **`datasource-proxy` or Hypersistence `SQLStatementCountValidator`.** Not needed. Both wrap the `DataSource` and count per thread ([datasource-proxy 1.11 user guide § 5.3](https://jdbc-observations.github.io/datasource-proxy/docs/current/user-guide/)). The test tree already owns `SqlStatementCounter`, the same mechanism in about 70 lines. QuickPerf and `spring-hibernate-query-utils` have no release for Spring Boot 4 or Hibernate 7.
- **Hibernate Statistics `entityFetchCount` as the N+1 signal.** Rejected for the same reasons as `Statistics`.
- **A statement budget only.** Rejected. It finds an N+1 but not a query that cannot use its index, which was the 120 s Activity view.
- **A plan check on realistic data in CI.** Rejected for now. It needs a staging-size database in CI. Instead, the pull request reports the plan on staging (decision 4).

## Decision

1. **A read for a screen or an API response returns a record shaped like that screen.**
   The method is on a Spring Data repository and runs in a read-only transaction, as ADR 0075 defines.
   It selects only what the screen shows, in one statement or a few, with keyset or offset pagination in SQL.
2. **JPQL with an explicit `select new` when JPQL can say it. Native SQL into a record when it cannot.**
   JPQL cannot say a common table expression, a window function, `DISTINCT ON`, `LATERAL` or a PostgreSQL operator.
   A native read maps to its record by column position, and nothing checks it before it runs.
   Thus, an integration test runs each native read.
3. **Entities are for writes.**
   Load an entity to change it, to keep an invariant of its aggregate, or to use `@Version`, and read it in the transaction that writes it.
   Every to-one association is lazy, and no association is eager.
   A write path that needs an association fetches it in its query (`join fetch` or `@EntityGraph`).
4. **A pull request that changes a read reports the plan on staging-size data.**
   It gives `EXPLAIN (ANALYZE, BUFFERS)` for each changed statement on staging, or the generic plan where ANALYZE cannot run.
5. **Tests guard the cost.**
   - An endpoint that a main screen calls has an integration test that measures its request through `SqlRequestTrace`.
     The test asserts a statement budget and asserts that no statement repeats. It runs with few rows and with many rows, and the count must not change.
   - The same test passes the request's statements to `QueryPlans`.
     It plans each one with `EXPLAIN (GENERIC_PLAN)` and sequential scans off, and fails on a filtered sequential scan that no index can replace.
     On PostgreSQL 18 such a scan shows `"Disabled": true` at any table size (measured on staging, PostgreSQL 18.6). This finds a query that leaves out the predicate of a partial index.
   - An architecture rule rejects an eager association.

## Consequences

- About 440 entity reads and 125 interface projections do not follow the pattern. A read converts when a change touches it or when it is measured hot. The main screens convert first.
- A native read can break at run time when a column moves. Its integration test is the only check.
- A record per screen adds types. Each one sits next to its query and replaces a DTO factory and its lazy reads.
- The guard runs only on the endpoints that have a measured test. The plan check sees the plans of an almost empty test database with sequential scans off. It proves that an index can serve a statement, not that PostgreSQL chooses it on real data. Decision 4 covers that.

## Revisit trigger

- Spring Data supports Jakarta Data repositories, or validates native queries at startup.
- Spring Framework documents a transactional `StatelessSession` for `JpaTransactionManager`. Then Hibernate Data Repositories can replace the startup check with a compile-time check without a second transaction setup.
- A CI job gets a staging-size database. Then decision 4 can become a test.
