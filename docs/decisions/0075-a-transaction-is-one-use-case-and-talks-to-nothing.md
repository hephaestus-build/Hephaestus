# ADR 0075: A transaction is one use case and talks to nothing

**Status:** Accepted
**Date:** 2026-11-03
**Authors:** Felix T.J. Dietrich

## Context

On 2026-11-03, the server had 1,198 `@Transactional` lines, 86 `REQUIRES_NEW` boundaries, `TransactionTemplate` in 93 files and 9 hand-written after-commit callbacks.
Only the 13 `@ApplicationModuleListener` methods write an event publication row.
About 60 other after-commit listeners lose their effect when the process stops between the commit and the listener.
A read of the code found these defects:

- A synchronous after-commit listener called a `REQUIRED` boundary.
  It joined the transaction that had already committed, so its write was lost.
  The cancel of a review left its job running.
- An account export that a restart interrupts stays in progress, and each later export request fails with 409.
- A disconnect that a restart interrupts leaves the provider token or installation active.
- 20 transactions stay open across a GitHub, GitLab, Slack or NATS call.
  Such a call can wait for minutes while it holds a pooled connection and row locks.
- An unlocked read-modify-write lets a workspace suspend overwrite a purge.

The framework already has a mechanism for each of these problems.
The code did not use these mechanisms in a consistent way.

## Decision drivers

- A side effect that the product relies on survives a crash.
- A slow provider cannot hold a database connection or a row lock.
- A rule that a test can check has a test.
- Use no new framework or table. Spring, Spring Modulith and PostgreSQL already supply what we need.

## Considered options

1. Fix each defect where it occurs and add no rule.
2. Write a hand-made outbox table and a relay.
3. Use the Spring Modulith event publication registry for durable side effects.
   Keep transactions short and free of network calls.
   Enforce the rules that a test can check.

## Decision

We chose option 3.
[Transactions and side effects](../contributor/transactions.md) is the single source for the rules and their sources.
In short, one use case is one declarative transaction.
A network call runs outside every transaction.
Durable work after commit is an `@ApplicationModuleListener`, and each consumer is idempotent.
An after-commit listener runs on another thread or in a transaction of its own.

Option 2 duplicates the registry that [ADR 0044](0044-email-is-a-notification-transport.md) already operates.
Option 1 leaves the next defect to code review.

## Consequences

- An architecture test refuses an after-commit listener that has no boundary of its own.
  The 12 synchronous listeners that remain now declare `NOT_SUPPORTED`.
  The review cancel is an `@ApplicationModuleListener`, so a restart cannot lose it.
- Later changes move the account export, the provider teardown and the activity listeners to the registry.
  They delete `TransactionCallbacks`, and a test then refuses `registerSynchronization`.
- More events write a publication row. Completed rows are deleted (`completion-mode: delete`).
- At-least-once delivery can repeat an email after a crash. ADR 0044 accepts this.

## Revisit trigger

Reopen this ADR if publication rows become a measured write bottleneck.
Reopen it also if Spring Modulith changes how it resubmits failed publications.
