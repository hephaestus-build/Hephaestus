# ADR 0075: A transaction is one use case and talks to nothing

**Status:** Accepted
**Date:** 2026-11-03
**Authors:** Felix T.J. Dietrich
**Relates to:** [ADR 0076](0076-reads-are-shaped-like-the-screen.md) (database reads), [Transactions and side effects](../contributor/transactions.md) (the rules).

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

## Bounded lifecycle calls

`GitLabSuspendedWebhookCleanup` holds the connection lifecycle row lock while it deletes a suspended connection's group hook.
Reactivation takes the same lock before it can adopt that hook.
A state check followed by an unlocked DELETE would allow reactivation to commit between the check and the deletion.
Checking the state after deletion cannot restore a hook that the provider has removed.

The lock covers the current-state read, the provider calls and the clearing of the stored hook ID.
The GitLab client bounds listing, deletion and confirmation together to 15 seconds.
A failed call rolls back the clear.
Registry redelivery and the scheduled reconcile retry it.

A connection that is ACTIVE when the lock is obtained is left unchanged.
The reconcile also lists suspended connections with no stored hook ID.
It removes hooks whose URL belongs to that connection, including a registration that finished after suspension.
This follows the same lifecycle fence as provider teardown.
It adds no lock across unrelated connections.

[PostgreSQL row-level locks](https://www.postgresql.org/docs/current/explicit-locking.html#LOCKING-ROWS) last until transaction end.
[GitLab's group webhook API](https://docs.gitlab.com/api/group_webhooks/) deletes the specific hook ID.
An already absent hook counts as removed.

This fence orders confirmed provider calls against local reactivation.
A DELETE accepted remotely can finish after the local deadline releases the lock.
If a resumed connection adopts that same provider identity, the late DELETE can still remove it.
The cross-provider lifecycle repair tracks this availability risk separately.
It requires an adoption fence or provider identities that change with each lifecycle generation.
