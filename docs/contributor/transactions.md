---
title: Transactions and side effects
description: Where a transaction starts and ends, how work leaves it, and how concurrent writers stay correct.
page_type: reference
---

These rules apply to the server.
[ADR 0075](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0075-a-transaction-is-one-use-case-and-talks-to-nothing.md) records why we chose them.
Architecture tests enforce the rules that a test can see.
The other rules are for code review.
Some code that is older than ADR 0075 does not obey all the rules yet.
Do not copy it.

The rules use these versions: Spring Framework 7.0, Spring Modulith 2.1, Hibernate ORM 7 and PostgreSQL 18.
[Read and write the database](./read-and-write-the-database.mdx) owns the shape of a read and a write inside a transaction (ADR 0076).

## 1. One use case is one transaction

- Put `@Transactional` on a public method of the application service in the module that owns the rows.
- Do not put it on a controller. `ArchitectureTest` refuses this.
- A repository does not open a boundary that a caller needs.
- A read use case uses `@Transactional(readOnly = true)`.
- To divide a use case into units, move each unit to its own bean.
  Do not use `TransactionTemplate` or `REQUIRES_NEW` to avoid self-invocation.
  `SpringCorrectnessArchitectureTest` refuses self-invocation, and `final`, `static` and non-public boundaries.

Spring: "self-invocation (in effect, a method within the target object calling another method of the target object) does not lead to an actual transaction at runtime even if the invoked method is marked with `@Transactional`" ([Using @Transactional](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)).

## 2. Use programmatic transactions only around a network call

- Use `TransactionTemplate` only in a method that runs short database units with a network call between them.
  Examples are a sync page, the review runner (claim, then execute, then settle) and the NATS handler (commit, then acknowledge).
- Use `REQUIRES_NEW` only for a row that must stay after the caller rolls back, such as a failure record or an audit row.
  That row must not be a row that the caller has locked.
  Write the reason in one comment line.
- Two pooled connections for one thread on a frequent path are a defect.
  The pool has 20 connections on the server role and 32 on the worker role.

## 3. Do not call a network service inside a transaction

- Call a model, GitHub, GitLab, Slack, Outline, NATS, Kubernetes or SMTP only when no transaction is active.
- Use this sequence: read in a short transaction, call with no transaction, then write in a short transaction.
  The write checks the state again with a row lock or a compare-and-set.
- If a row lock must stay across a call, record the reason in an ADR.
  `GitLabTokenLifecycleService` and `ProviderTeardown` hold such a lock.
- `LlmCallPermits` refuses a model call that starts in a transaction.
- `database_transaction_http_requests_total` counts the HTTP requests that start in a transaction, by boundary.
  It shows which boundaries still break this rule.

PostgreSQL keeps row locks until the transaction ends ([Explicit locking](https://www.postgresql.org/docs/18/explicit-locking.html)).
A slow call therefore blocks every writer of the same rows, and it holds a pooled connection.

## 4. Side effects after commit use the event publication registry

An email is an action in the outbox, not a listener call.
A listener may enqueue the action. [The outbox](./outbox.md) has the rules and the kinds that it carries now.

- Publish an application event in the transaction of the use case.
  Consume it with `@ApplicationModuleListener`.
  The registry writes the publication row in the same transaction as the business row.
  `FailedEventPublications` sends a failed publication again.
- Do not call `TransactionSynchronizationManager.registerSynchronization`.
- For durable work, do not write the combination of `@Async`, `@Transactional(REQUIRES_NEW)` and `@TransactionalEventListener` yourself.
  That combination is `@ApplicationModuleListener` without the registry.
- Use a plain `@EventListener` or `BEFORE_COMMIT` for work that must commit or roll back with the publisher.
- Use a plain `@TransactionalEventListener` after commit only for an effect that the next poll repairs: a UI push, a cache eviction, a metric or a wake-up.
  The listener must be `@Async` or `@Transactional(propagation = REQUIRES_NEW)` or `NOT_SUPPORTED`.
  `SpringCorrectnessArchitectureTest` refuses a listener without one of them.

The listener needs its own boundary because of this behavior. After commit, "the transactional resources might still be active and accessible. As a consequence, any data access code triggered at this point will still "participate" in the original transaction, allowing to perform some cleanup (with no commit following anymore!)". Spring adds: "Use `PROPAGATION_REQUIRES_NEW` for any transactional operation that is called from here" ([TransactionSynchronization](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html)).
Prefer `NOT_SUPPORTED`.
It suspends the finished transaction.
Then each call that the listener makes starts its own transaction, and a network call runs outside a transaction.

Why the registry: Spring Modulith writes an entry for each listener "into an event publication log as part of the original business transaction". Without it, "if the listener fails for whatever reason, the event publication is lost, unless each listener actually implements its own safety net" ([Working with application events](https://docs.spring.io/spring-modulith/reference/events.html)).
This is the [transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html): "first store the message in the database as part of the transaction that updates the business entities".

## 5. A consumer that gets a message more than once is idempotent

- The registry and NATS JetStream deliver at least once.
  A crash after the effect and before completion sends the event again.
- A listener or a NATS handler writes by natural key (`INSERT … ON CONFLICT`), by compare-and-set on a state or a timestamp, or records the IDs that it processed ([Idempotent consumer](https://microservices.io/patterns/communication-style/idempotent-consumer.html)).
- Publish to JetStream with a `Nats-Msg-Id` header.
  JetStream keeps only one message for each ID in its duplicate window ([Publishing](https://docs.nats.io/learn/jetstream/publishing)).
- Acknowledge a NATS message after the handler commits.
  [Webhook delivery and retry order](./ingest-durability.md) has the consumer contract.

## 6. Concurrent writers

- A read-modify-write of a row that has more than one writer locks the row (`…ForUpdate`, `PESSIMISTIC_WRITE`).
  If a person edits the row over HTTP, use `@Version` and an ETag that answers 409 or 412.
- Lock in one order: workspace, then connection, then item, then the item's children.
  An outbox action locks before its domain row: its end moves that row in the same transaction ([The outbox](./outbox.md)).
  Sort advisory lock keys before you take them.
  PostgreSQL: "The best defense against deadlocks is generally to avoid them by being certain that all applications using a database acquire locks on multiple objects in a consistent order."
- Use `pg_advisory_xact_lock`. It is "automatically released at the end of the transaction".
  Use a session lock only on a dedicated connection that you release in `finally`.
- Work that calls a network service and must run one at a time takes a `ClusterLock`, not `synchronized`.
  More than one server runs, and an in-memory lock serializes only one of them.
  `ClusterLock` holds a session lock on a dedicated connection and has no transaction.
- A queue claims rows with `FOR UPDATE SKIP LOCKED` and a lease.
  PostgreSQL: `SKIP LOCKED` "is not suitable for general purpose work, but can be used to avoid lock contention with multiple consumers accessing a queue-like table" ([SELECT](https://www.postgresql.org/docs/18/sql-select.html)).
- Do not catch a constraint violation and then continue in the same transaction.
  PostgreSQL has already aborted that transaction.
  Use `INSERT … ON CONFLICT`.

## 7. Isolation

- READ COMMITTED is the default. Code assumes it.
- Use `REPEATABLE_READ` for a read-only snapshot.
- A transaction that writes at `REPEATABLE_READ` or higher retries SQLSTATE 40001 in a new transaction, outside any caller transaction.
  `ResultStoreService` shows the pattern.

PostgreSQL: "Read Committed is the default isolation level in PostgreSQL." At Repeatable Read, "applications using this level must be prepared to retry transactions due to serialization failures" ([Transaction isolation](https://www.postgresql.org/docs/18/transaction-iso.html)).

## 8. Batches

- Use one transaction for each page or chunk. Do not use one transaction for the full loop.
- For bulk work, use a bulk statement or a `StatelessSession`.
  Do not use `clear()`, `detach()` or `clearAutomatically` to hide stale state.

Hibernate: "if you find yourself thinking you *do* need them [`detach()` and `clear()`] in a certain situation, you should strongly consider an alternative solution: a *stateless session*." A stateless session "doesn't have a first-level cache (persistence context) … so all operations are executed immediately when they're explicitly called" ([Hibernate ORM 7 introduction](https://docs.hibernate.org/orm/7.0/introduction/html_single/Hibernate_Introduction.html)).
