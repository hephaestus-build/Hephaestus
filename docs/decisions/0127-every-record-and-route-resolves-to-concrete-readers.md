# ADR 0127: Every record and route resolves to concrete readers

**Status:** Accepted
**Date:** 2026-11-24
**Authors:** Felix T.J. Dietrich

**Amended by:** [ADR 0164](0164-staff-see-no-private-content-and-support-starts-with-the-person.md)

**Builds on:** [ADR 0084](0084-every-statement-on-workspace-data-binds-its-workspace.md) (workspace tenancy in SQL), [ADR 0126](0126-a-person-has-one-relationship-and-a-personal-space.md) (the relationship and the personal space)

**Relates to:** ADR 0121 (proposed in #4862, the audience rule), ADR 0141 (proposed in #4868, research datasets), [ADR 0134](0134-the-authorization-recheck-runs-at-three-boundaries-with-two-authorization-generations.md) (the recheck of current access)

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
- Background work, research exports and aggregates find rows without a general way around the owner rule.
- A stale provider permission never becomes lasting access.

## Considered options

1. **Audience labels on records.** A label such as `PRIVATE` or `TEAM` does not establish who owns a record or who may read a route. Two code paths can read one label in two ways.
2. **Owner checks in application code only.** Each query must remember the filter. One forgotten filter is a leak that no test of other queries finds.
3. **A server role with `BYPASSRLS` for background work.** Every job then reads all rows. A bug in a job is a leak across people.
4. **Concrete readers, row-level security on person tables, and five narrow definer functions (chosen).**

## Decision

Option 4.

1. **Every record and every route resolves to concrete readers.** `Readers` is a sealed value type.
   - The kinds are the owner, workspace members, the readers of a provider resource and a Slack identity.
   - A support recipient and anyone, for public work, are kinds too.
   - A Slack channel resolves to every reader of that channel, as ADR 0123 (proposed in #4862) decides.
   - A check that one set covers another never widens a set.
   - Unknown readers refuse.
   - A guessed ID returns 404.
2. **Row-level security binds each person's rows to their account.** It runs on every person-owned table. The schema names each table.
3. **The owner is set for each transaction.** `OwnerScope` calls `set_config('hephaestus.owner_account', ?, true)` inside the transaction. Every policy reads it through `public.current_owner_account()`.
4. **That function is safe in a connection pool.** It turns an empty setting into NULL. A later query without an owner then sees no person row and does not fail.
5. **Two roles, as on main.** The server connects as `hephaestus_app`, and every policy applies to it. `hephaestus_owner` owns the tables and runs the migrations. No role has `BYPASSRLS`.
6. **Person tables enable row-level security but do not force it.** The owner role can then run migrations. A startup check refuses a server connection as the owner role.
7. **Local development and CI use the same roles,** so a superuser connection cannot hide a missing policy.
8. **Five narrow definer functions cross owners.** Nothing else does.
   - `public.mentoring_due_work(kind, limit)` returns only kind, ID, workspace, account and due time, never content. The scheduler then sets the owner scope from that row.
   - `public.person_row_sweep` deletes for retention and erasure by one closed rule.
   - `public.support_share_mark_read` changes only the read count and the last read time.
   - `public.research_export_rows(release)` reads the rows of one research release. It is the research module's function.
   - `public.person_row_counts(kind, workspace, period)` returns counts only, under the disclosure rule of [ADR 0125](0125-hephaestus-is-never-a-performance-instrument.md).
   - Each is `SECURITY DEFINER`, owned by `hephaestus_owner`, with a fixed `search_path` and `REVOKE ALL … FROM PUBLIC`.
9. **The research export function has one caller.**
   - Only the research export job calls it. The job has no human reader, and it builds a release into sealed staging. An architecture test enforces the one caller.
   - The function checks consent, redaction and the disclosure rule of [ADR 0125](0125-hephaestus-is-never-a-performance-instrument.md).
   - The sealed bytes leave staging only after the two approvals of ADR 0141 (proposed in #4868).
   - Only the approved recipient's grant can read the sealed bytes. The export job remains the sole caller of the function. No staff role, no staff route and no admin download reaches it.
10. **Workspace tenancy stays.** The workspace statement inspector of [ADR 0084](0084-every-statement-on-workspace-data-binds-its-workspace.md) runs beside row-level security.
11. **Provider access is fresh.**
    - A cache of provider access lists has a bounded lifetime for each adapter, one hour by default.
    - A restricted resource is checked live again at read and at handoff.
    - A resource whose visibility the adapter cannot prove is refused.
12. **An observation about a role holder with no account has no reader.** No staff path shows it instead.

### What this changes in other ADRs

- [ADR 0084](0084-every-statement-on-workspace-data-binds-its-workspace.md) stays. Row-level security adds a second boundary inside the workspace.
- ADR 0121 (proposed in #4862) states the audience rule. A turn reads only sources that every reader of its output may read. This ADR gives the readers that the rule compares.
  - ADR 0121's `conversation.audience` does not conflict with option 1. It labels a thread, not a record, and it follows from the place kind. The thread's route still resolves to concrete readers.

## Consequences

- Good: a forgotten owner filter returns no person row instead of another person's row.
- Good: background work cannot read content through its search for due work.
- Good: a research export and an aggregate cross owners only through a function that checks its own rule. No staff role holds a general read.
- Good: a stale provider permission expires within one hour.
- Bad: every transaction on person data must set the owner scope. A missing scope returns nothing, which can look like an empty page.
- Bad: five definer functions run with the owner's rights. A change to one of them needs a security review.
- Bad: the live recheck of restricted resources adds provider calls at read and at handoff.

## Confirmation

Not enforced. The implementing issues add the checks below, and review of each implementing pull request catches a missing one. They are:

- A row-level security test fails when a person table has no policy. It also fails when a query as another account returns a row.
- A pool test fails when a pooled connection keeps the owner of an earlier transaction.
- A startup test fails when the server starts with a connection as `hephaestus_owner`.
- A definer test fails when a routine other than the five new functions and `maintain_auth_event_partitions` is a definer function.
- An architecture test fails when a class other than the research export job calls `research_export_rows`.
- A research test fails when `research_export_rows` returns a row for a release that is not approved.
- A count test fails when `person_row_counts` returns a count of 1 to 4 people.
- ReadersTest fails when a `covers` check widens a set, or unknown readers do not refuse.

## Revisit trigger

- A query that needs data of two people in one statement, such as a shared goal.
- Profiling shows that the owner scope or the policies cost more than 10 % of the time of a hot read.

## Sources

- PostgreSQL 18, [Row Security Policies](https://www.postgresql.org/docs/18/ddl-rowsecurity.html). Roles "with the `BYPASSRLS` attribute always bypass the row security system". Also, "table owners normally bypass row security". This is why no role has `BYPASSRLS` and the server never connects as the owner.
