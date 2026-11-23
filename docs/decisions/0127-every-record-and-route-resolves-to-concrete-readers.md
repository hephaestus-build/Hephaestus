# ADR 0127: Every record and route resolves to concrete readers

**Status:** Proposed
**Date:** 2026-11-23
**Authors:** Felix T.J. Dietrich

**Builds on:** [ADR 0084](0084-every-statement-on-workspace-data-binds-its-workspace.md) (workspace tenancy in SQL), [ADR 0126](0126-a-person-has-one-relationship-and-a-personal-space.md) (the relationship and the personal space)

**Relates to:** ADR 0121 (proposed in #4862, the audience rule), [ADR 0134](0134-the-authorization-recheck-runs-at-three-boundaries-with-two-authorization-generations.md) (the recheck of current access)

## Context

[ADR 0084](0084-every-statement-on-workspace-data-binds-its-workspace.md) binds every statement on workspace data to its workspace.
That keeps teams apart. It does not keep two people in one team apart.
Hephaestus now stores a person's goals, lessons, noted preferences and private check outcomes in the team workspace.
A teammate must never read them, also through a bug in one query.

A record can also have readers outside the workspace: a Slack identity, a support recipient, or anyone for public work.
A label such as "private" or "team" does not say who reads.
The real constraint is that every read and every route names the people who read it, so that a check can compare them.

## Decision drivers

- A person's rows never reach another person, even when a query forgets a filter.
- A check compares concrete readers, not labels.
- Background work finds due rows without a way around the owner rule.
- A stale provider permission never becomes lasting access.

## Considered options

1. **Audience labels on records.** A label such as `PRIVATE` or `TEAM` does not establish who owns a record or who may read a route. Two code paths can read one label in two ways.
2. **Owner checks in application code only.** Each query must remember the filter. One forgotten filter is a leak that no test of other queries finds.
3. **A server role with `BYPASSRLS` for background work.** Every job then reads all rows. A bug in a job is a leak across people.
4. **Concrete readers, row-level security on person tables, and three narrow definer functions (chosen).**

## Decision

Option 4.

1. **Every record and every route resolves to concrete readers.** `Readers` is a sealed value type.
   - The kinds are the owner, workspace members, the readers of a provider resource and a Slack identity.
   - A support recipient and anyone, for public work, are kinds too.
   - A check that one set covers another never widens a set.
   - Unknown readers refuse.
   - A guessed ID returns 404.
2. **Row-level security binds each person's rows to their account.** It runs on every person-owned table. The schema names each table.
3. **The owner is set for each transaction.** `OwnerScope` calls `set_config('hephaestus.owner_account', ?, true)` inside the transaction. Every policy reads it through `public.current_owner_account()`.
4. **That function is safe in a connection pool.** It turns an empty setting into NULL. A later query without an owner then sees no person row and does not fail.
5. **Two roles, as on main.** The server connects as `hephaestus_app`, and every policy applies to it. `hephaestus_owner` owns the tables and runs the migrations. No role has `BYPASSRLS`.
6. **Person tables enable row-level security but do not force it.** The owner role can then run migrations. A startup check refuses a server connection as the owner role.
7. **Local development and CI use the same roles,** so a superuser connection cannot hide a missing policy.
8. **Background work uses three narrow definer functions.**
   - `public.mentoring_due_work(kind, limit)` returns only kind, ID, workspace, account and due time, never content. The scheduler then sets the owner scope from that row.
   - `public.person_row_sweep` deletes for retention and erasure by one closed rule.
   - `public.support_share_mark_read` changes only the read count and the last read time.
   - Each is `SECURITY DEFINER`, owned by `hephaestus_owner`, with a fixed `search_path` and `REVOKE ALL … FROM PUBLIC`.
9. **Workspace tenancy stays.** The workspace statement inspector of [ADR 0084](0084-every-statement-on-workspace-data-binds-its-workspace.md) runs beside row-level security.
10. **Provider access is fresh.**
    - A cache of provider access lists has a bounded lifetime for each adapter, one hour by default.
    - A restricted resource is checked live again at read and at handoff.
    - A resource whose visibility the adapter cannot prove is refused.
11. **An observation about a role holder with no account has no reader.** No staff path shows it instead.

### What this changes in other ADRs

- [ADR 0084](0084-every-statement-on-workspace-data-binds-its-workspace.md) stays. Row-level security adds a second boundary inside the workspace.
- ADR 0121 (proposed in #4862) states the audience rule. A turn reads only sources that every reader of its output may read. This ADR gives the readers that the rule compares.

## Consequences

- Good: a forgotten owner filter returns no person row instead of another person's row.
- Good: background work cannot read content through its search for due work.
- Good: a stale provider permission expires within one hour.
- Bad: every transaction on person data must set the owner scope. A missing scope returns nothing, which can look like an empty page.
- Bad: three definer functions run with the owner's rights. A change to one of them needs a security review.
- Bad: the live recheck of restricted resources adds provider calls at read and at handoff.

## Confirmation

Not enforced yet. The implementation adds these tests:

- A row-level security test fails when a person table has no policy. It also fails when a query as another account returns a row.
- A pool test fails when a pooled connection keeps the owner of an earlier transaction.
- A startup test fails when the server starts with a connection as `hephaestus_owner`.
- A definer test fails when a routine other than the three new functions and `maintain_auth_event_partitions` is a definer function.
- `ReadersTest` fails when a `covers` check widens a set, or unknown readers do not refuse.

## Revisit trigger

- A query that needs data of two people in one statement, such as a shared goal.
- Profiling shows that the owner scope or the policies cost more than 10 % of the time of a hot read.

## Sources

- PostgreSQL 18, [Row Security Policies](https://www.postgresql.org/docs/18/ddl-rowsecurity.html). Roles "with the `BYPASSRLS` attribute always bypass the row security system". Also, "table owners normally bypass row security". This is why no role has `BYPASSRLS` and the server never connects as the owner.
