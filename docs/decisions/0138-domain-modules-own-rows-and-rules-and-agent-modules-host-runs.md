# ADR 0138: Domain modules own rows and rules, and agent modules host runs

**Status:** Accepted
**Date:** 2026-11-24
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0099](0099-aggregates-own-their-lifecycle-and-module-boundaries.md) rules 5, 6 and 8

**Builds on:** [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) (one server role), [ADR 0084](0084-every-statement-on-workspace-data-binds-its-workspace.md) (every statement binds its workspace)

**Relates to:** ADR 0120 (proposed in #4862, threads and inbox items), [ADR 0135](0135-every-client-and-channel-uses-one-hephaestus-api.md) (the change feed)

## Context

The conversation agent adds new rows: the relationship of a person with Hephaestus, personal records, threads, inbox items, check-ins and tasks.
It also adds new run kinds, routes and tools that read and write those rows.

If one module held the rows and the runs, it would be large, and its rules would mix.
If the `agent` module held the runs and a new `mentoring` module held the rows, each would call the other.
`agent` starts a turn that reads threads, and `mentoring` enqueues the work that `agent` runs.
That is a module cycle, and Spring Modulith refuses it.

Person rows add a second constraint.
A person must never see another person's rows, also in the same workspace.
Tenancy by workspace ([ADR 0084](0084-every-statement-on-workspace-data-binds-its-workspace.md)) is necessary but not sufficient.

## Decision drivers

- No module cycle.
- A rule that one module owns is not repeated in another.
- A boundary that a test checks, not one that review remembers.
- No new runtime role and no new database role.

## Considered options

1. **One large `mentoring` module for rows and runs.** It has no cycle, but its rules mix, and the independence of relationship, learning and threads is only a convention.
2. **Seven new modules,** with separate modules for surfaces, research, evaluation and voice. Research already has a module on main. Voice is 1.x. Surfaces fold into the API and the existing integration modules. Four of the seven have no 1.0 owner.
3. **Domain and runtime in one module for each concept.** `mentoring` would host its own run kinds, so it would depend on `agent`, and `agent` on it. This is the cycle.
4. **Three domain modules, a split between domain and runtime, and architecture tests (chosen).**

## Decision

Option 4.

1. **Three new domain modules.**
   - `relationship` holds the relationship row, its settings, the change feed and the settings of each workspace.
   - `learning` holds personal records, transfers and the reads of learning records.
   - `mentoring` holds threads and their inboxes, the contact policy, check-ins, task records and the due-work function.
2. **Existing modules grow.** `practices.research` gets the research additions (ADR 0141, proposed in #4868). `integration.slack` gets the routing of Slack direct messages. `notification` gets the new push kinds.
3. **Domain and runtime are split.** A domain module owns rows and rules. An `agent.<owner>` package hosts the run kinds, gateway routes and tools of that owner. The runtime depends on the domain, never the reverse.
4. **No module reads the tables of another module.** It calls a named interface, as ADR 0099 rule 5 says for writes. This rule now covers reads too.
5. **A foreign key that points against a module edge becomes an ID** with a completeness test.
6. **Every person table carries a readers annotation and a tenancy annotation.** The architecture tests read them. The rule for who reads a person's rows belongs to ADR 0127 (proposed in #4863), not to this ADR.
7. **Events between modules carry IDs only.** Every change-feed object type has a producer in its owning module.
8. **No new runtime role and no new database role.** Main's server role and its `migrate` profile stay. Narrow `SECURITY DEFINER` functions, created by the migration, serve the reads across owners that a job needs.
9. **One injectable clock.** No module reads the system clock directly.
10. **One application transaction coordinator owns the turn commit.** It calls each owner's port for the ordered locks and the staged writes. It lives in the runtime, so no domain module depends on it.

### What this changes in other ADRs

- [ADR 0099](0099-aggregates-own-their-lifecycle-and-module-boundaries.md) rule 5 says that only the owning module writes an aggregate. It now also says that only the owning module reads its tables.
- ADR 0099 rule 6 gets three new closed modules with declared dependencies.
- ADR 0099 rule 8: the context map table of `system-design.mdx` gets the three modules and the split between domain and runtime.

## Consequences

- Good: Spring Modulith refuses a cycle between `agent` and `mentoring` at build time.
- Good: a test finds a person table that has no declared audience.
- Good: a job that finds due work uses a narrow function, not a wider role.
- Bad: a run kind and its domain live in two packages, so one feature touches both.
- Bad: reads across modules need a named interface, which adds code.
- Bad: every new person table needs two annotations and a row in the person-data catalog.

## Confirmation

Not enforced. The implementing issues add the checks below, and review of each implementing pull request catches a missing one. They are:

- `ModulithVerificationTest` and `ModuleDeclarationTest` fail on an undeclared edge or a cycle.
- TableOwnershipArchTest fails when a module reads a table of another module.
- An architecture test fails when a person table has no readers annotation or no tenancy annotation.
- DefinerFunctionAllowlistTest fails on a `SECURITY DEFINER` function that is not on its list.
- An architecture test fails when an event between modules carries more than IDs.
- An architecture test fails when a log field is not on its allowlist.
- An architecture test fails when a staff activity view reads conversations or personal records.

## Revisit trigger

- A feature needs a dependency from a domain module to its runtime package.
- A named interface between two domain modules needs more than one change in three releases. Then the two modules may be one.

## Sources

- Spring Modulith, [Fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html). Allowed dependencies and named interfaces define a module edge, and verification refuses a cycle. This gives decisions 3 and 4.
- ArchUnit, [User Guide](https://www.archunit.org/userguide/html/000_Index.html). Rules on packages, annotations and calls run as unit tests. This gives the checks of decisions 4 to 7.
