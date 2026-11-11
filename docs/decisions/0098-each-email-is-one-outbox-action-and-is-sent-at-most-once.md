# ADR 0098: Each email is one outbox action that the deciding transaction enqueues, and Hephaestus sends it at most once

**Status:** Accepted
**Date:** 2026-11-10
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0044](0044-email-is-a-notification-transport.md)

**Builds on:** [ADR 0079](0079-one-agent-system.md), [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md), [ADR 0075](0075-a-transaction-is-one-use-case-and-talks-to-nothing.md), [ADR 0096](0096-1-0-is-one-stopped-cutover-from-1-0-0-rc-1-and-keeps-nothing-for-0-x.md)

**Relates to:** [ADR 0073](0073-product-feedback-is-worked-on-in-github.md), [ADR 0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md)

## Context

The title and the Decision of ADR 0044 say that the event publication registry delivers email.
Since ADR 0079, each email is an `email` action in the `agent_action` outbox, and the registry is not the record of a delivery.
ADR 0044 also bundled about twelve rules and the survey records, which the 1.0 cutover removes.
This ADR states the notification email of 1.0 as rules.
[The outbox](../contributor/outbox.md) and [Transactions and side effects](../contributor/transactions.md) keep the detailed design.

An email has no readback. After a crash, the server cannot ask the relay whether it accepted a message.
Thus, the server must choose between a possible duplicate and a possible loss.

## Decision drivers

- An email reaches a person, so a duplicate or a stale message costs trust.
- One record for each effect on the outside world ([ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) rule 10).
- A transaction talks to nothing ([ADR 0075](0075-a-transaction-is-one-use-case-and-talks-to-nothing.md)). The SMTP call runs after the commit.
- Erasure must stop an email that has not gone yet.
- An operator can run Hephaestus with no relay.

## Considered options

1. **The registry as the delivery record (ADR 0044).** The listener sends after the commit. A crash after relay acceptance sends the email again. Erasure cannot find a queued email by its account.
2. **An `email` action in the one outbox (chosen).** The action has an idempotency key, a write fence and an account. [ADR 0079](0079-one-agent-system.md#considered-options) prices the one outbox against a workflow engine.
3. **A relay or a vendor API with an idempotency key.** It removes the loss on a crash. Each operator then needs that vendor, and the code names no relay.

## Decision

1. **The `notification` module owns notification email.** It holds the listeners, the templates and the subscriptions. `EmailKind` lists every email. Thymeleaf renders HTML and text from one model.
   Enforced by: `NotificationArchitectureTest#shouldKeepPersistenceOutOfEmailTransport` and `EmailRendererTest#shouldRenderSubjectTextAndHtmlForEveryKind`.
2. **One SMTP gateway.** `EmailAdapter` is the only adapter of `ActionKind.EMAIL` and the only SMTP writer. Each message has text and HTML, a Message-ID from its idempotency key, and the RFC 3834 headers. Logs name the kind and the Message-ID, never the address.
   Enforced by: `OutboundEgressArchitectureTest#shouldKeepSmtpWritesInsideDeclaredGateway`, and `EmailAdapterTest` (`#shouldSendMultipartMessageWithSenderIdentityAndAutomationHeaders`, `#shouldGiveEachActionOneStableMessageId`, `#shouldNeverLogRelayRepliesOrExceptionsEvenAtDebugLevel`).
3. **Each email is one `email` action** in the outbox of [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) rule 10, with the key `email:<kind>:<address key>`.
4. **The deciding transaction enqueues the action.** A `BEFORE_COMMIT` listener in `notification` inserts it, so the action commits with the change or not at all.
   1.0 target: on main, the source publishes an event, the registry stores it, and an `@ApplicationModuleListener` enqueues after the commit. Each email thus crosses two durable queues. No pull request delivers it yet.
   Enforced by: `NotificationArchitectureTest#shouldRunPlainTransactionalListenersOnlyBeforeCommit`. No rule refuses an `@ApplicationModuleListener` in `notification` yet.
5. **At most once.** An attempt whose write may have started ends `UNCERTAIN` and never runs again. The server retries only a failure that proves the relay did not accept the message. An email gets at most 24 attempts.
   Enforced by: `ActionStoreIntegrationTest#shouldNeverTakeAnEmailAgainAfterItsWriteStarted` and `#shouldEnforceInDatabaseWhatEachKindDeclares`, and `EmailAdapterTest#shouldNeverSendAgainWhenTheRelayLeftTheOutcomeUnclear` and `#shouldRetryOnlyWhatTheRelayProvablyDidNotAccept`.
6. **The adapter builds each message at send time.** The action holds the event, not the message. Each attempt checks again the expiry, the verified address, the account status, the role and the subscription. An optional email also checks when the subscription started, so a later opt-in does not revive an older request.
   Enforced by: `NotificationSubscriptionIntegrationTest#shouldKeepOldRequestsCancelledAfterReSubscribingToAnyOptionalKind` and `AccountSecurityEmailIntegrationTest#shouldWithholdSecurityNoticesForInactiveAccounts`.
7. **Silent Mode withholds every email at the gateway.** This includes the admin test email. A withheld email ends `CANCELLED` and never runs again. The gateway exempts no kind.
   Enforced by: `EmailAdapterTest#shouldWithholdUnderSilentModeWithoutTouchingTheRelay` and `EmailAdminControllerIntegrationTest#shouldReportSilentModeInsteadOfSending`.
8. **The emails at 1.0** are these `EmailKind` values:
   - Essential: `ACCOUNT_SECURITY_CHANGED` (a sign-in identity, instance admin access or passkeys changed) and `TEST_MESSAGE` (an instance admin proves the relay).
   - Optional: `PRODUCT_FEEDBACK` (to opted-in instance admins: a report not on GitHub, or a cleanup that waits). It carries no report text.
   - Optional: `PRODUCT_FEEDBACK_UPDATE` (to the reporter, [ADR 0073](0073-product-feedback-is-worked-on-in-github.md) decision 11) and `WORKSPACE_ALERT` (a connection problem, to workspace admins).
   - Optional: `MENTOR_DIGEST` (the weekly digest note for a developer whom Slack cannot reach, [ADR 0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md)).

   1.0 target: `ACCOUNT_DELETION_SCHEDULED` and `AccountDeletionEmailListener` go, with their templates and their queued actions. Account deletion purges at once ([ADR 0096](0096-1-0-is-one-stopped-cutover-from-1-0-0-rc-1-and-keeps-nothing-for-0-x.md)). No pull request delivers it yet.
   Not enforced. Review of a new `EmailKind` catches a new email. `EmailAdapter` refuses two notices for one kind at startup.
9. **A person can turn off each optional email, and no essential email.** Product feedback updates start on. The other optional emails start off. Only an instance admin can turn on `PRODUCT_FEEDBACK`. An opt-in needs a verified address. An opt-out never does.
   Enforced by: `NotificationSubscriptionIntegrationTest` (`#shouldDefaultToReporterUpdatesWithoutCreatingRows`, `#shouldRefuseAdministratorSubscriptionsForAnOrdinaryAccount`, `#shouldRefuseEmailOptInUntilTheCurrentAccountsContactIsVerified`, `#shouldAllowDisablingOneSubscriptionAfterContactLossButRejectReEnablingIt`).
10. **Each optional email has a one-click unsubscribe link.** The link holds a random token for one account and one kind. It can only turn that kind off. A `POST` unsubscribes, and a `GET` opens a confirmation page. Only an HTTPS link advertises one-click.
    Enforced by: `NotificationSubscriptionIntegrationTest#shouldRequireExplicitAnonymousPostAndLeaveOtherSubscriptionsEnabled`, and `EmailAdapterTest#shouldAdvertiseOneClickOnlyForHttpsUnsubscribeLinks` and `#shouldRefuseOptionalMailWithoutAnUnsubscribeCapability`.
11. **Shared capacity.** A PostgreSQL-backed Bucket4j budget limits attempts across all replicas. The optional budget is smaller than the total, so essential email keeps capacity. When the store fails, the attempt waits.
    Enforced by: `EmailRateLimiterTest#shouldShareCapacityAcrossSendersAndReserveEssentialAttempts` and `#shouldWithholdWhenCapacityStoreIsUnavailable`.
12. **The transport is Spring Boot's `spring.mail.*`, and the sender is `hephaestus.email.*`.** A blank host registers no sender, and email is then off. Authenticated SMTP requires TLS. SMTP does not gate startup or readiness. [Production setup § Email](../admin/production-setup.mdx#email) owns the settings.
    Enforced by: `EmailTransportConfigurationTest` (`#shouldRegisterNoSenderWhenTheHostIsBlank`, `#shouldRejectAuthenticatedSmtpWithoutRequiredEncryption`).
13. **Erasure deletes the queued email.** Account erasure deletes the account's actions in every state and its subscriptions ([ADR 0079](0079-one-agent-system.md#update--2026-11-09-erasure-deletes-the-actions-of-an-account)). An export holds the choices, never a token.
    Enforced by: `ActionStoreIntegrationTest#shouldDeleteTheActionsOfAnErasedAccount` and `NotificationSubscriptionIntegrationTest#shouldExportOnlyChoicesAndEraseOnlyTheTargetAccountsSubscriptions`.

## What changed, and why

- **The outbox is the delivery record, not the registry** (ADR 0044 § Decision, ADR 0079). The registry writes a log entry for each listener "as part of the original business transaction". It records whether a listener ran, not whether the relay accepted a message. It thus repeated an email after a crash and could not find a queued email by its account.
- **Email is at most once, not at least once** (ADR 0044 § Consequences). Email has no readback. A duplicate security notice or alert costs more trust than a rare lost one.
- **One durable queue for each email** (rule 4). ADR 0044 needed the registry for durability. The outbox now gives it, so a second queue adds a second retry clock and a second alert.
- **No survey email and no account-deletion email.** The 1.0 cutover removes the survey records. Account deletion has no cooldown, so no confirmation is owed ([ADR 0096](0096-1-0-is-one-stopped-cutover-from-1-0-0-rc-1-and-keeps-nothing-for-0-x.md)).
- **Product feedback updates start on.** ADR 0044 said "All optional categories start off." That rule is superseded for reporter updates only. [ADR 0073](0073-product-feedback-is-worked-on-in-github.md) decision 11 and [#3588](https://github.com/hephaestus-build/Hephaestus/pull/3588) decided that the reporter gets follow-up email.
  A reporter update is a transactional email that the person asked for when they filed the report. It is not marketing.
  Each one carries a one-click way to turn it off (rule 10, RFC 8058). This is the reverse state of the default.

## Consequences

- Good: one row for each email. An operator watches one queue, and erasure deletes one row.
- Good: no duplicate email after a crash, and each Message-ID is stable for one action.
- Good: a new email adds one `EmailKind`, one notice and two templates. The gateway, Silent Mode and the budget come with it.
- Bad: a crash after the write starts can lose an email. A security notice can then be missing, so no flow depends on an email as its only record.
- Bad: relay acceptance does not prove inbox delivery.
- Bad: until rule 4 ships, each email crosses two queues, with two alerts (`HephaestusEventPublicationsOverdue`, `HephaestusOutboxStalled`).
- Bad: a fan-out to many admins inserts one row for each recipient in the deciding transaction.
- Bad: the relay must DKIM-sign both unsubscribe headers. The operator configures this, and Hephaestus cannot check it.

## Confirmation

Each rule above names its test.
Rule 4 has no rule against an `@ApplicationModuleListener` in `notification`. Review catches it until the rule ships with the move.
Rule 8 has no test that lists the emails. Review of `EmailKind` catches a new one.

## Revisit trigger

- An operator reports a lost security notice, or `notification.email.delivery` counts `unconfirmed` outcomes every month.
- A supported relay offers an idempotency key or a readback. Then measure at-least-once delivery with readback.
- A second notification transport, such as web push, is proposed.
- Optional email regularly uses its whole daily budget.

## Sources

- IETF, [RFC 8058](https://www.rfc-editor.org/rfc/rfc8058): the header "MUST contain one HTTPS URI".
  The POST "MUST NOT include cookies, HTTP authorization, or any other context information".
  Both headers "MUST be covered by the signature".
  Thus, the token is the only credential, one-click needs an HTTPS link (rule 10), and the relay must sign.
- Spring Modulith, [Working with application events](https://docs.spring.io/spring-modulith/reference/events.html): the registry writes entries "into an event publication log". It does so "as part of the original business transaction".
  "In case the listener fails, the log entry stays untouched."
  The registry records a listener, not a relay answer. Thus, it is no longer the delivery record.

## Already priced and declined

Each line links the section of ADR 0044 that priced the option.

- [ADR 0044](0044-email-is-a-notification-transport.md#context): email as a provider integration under `integration/`, with connections and capabilities.
- [ADR 0044](0044-email-is-a-notification-transport.md#context): a fire-and-forget `@Async` listener.
- [ADR 0044](0044-email-is-a-notification-transport.md#context): a hand-written outbox table. ADR 0079 reverses this rejection with the one outbox.
- [ADR 0044](0044-email-is-a-notification-transport.md#context): React Email in place of Thymeleaf.
- [ADR 0044](0044-email-is-a-notification-transport.md#decision): an asynchronous parent fan-out, which duplicates its children after a crash.
- [ADR 0044](0044-email-is-a-notification-transport.md#decision): expiry as a resubmission filter of the registry.
- [ADR 0044](0044-email-is-a-notification-transport.md#decision): a second delivery schedule, such as a digest, for product-feedback alerts.
- [ADR 0044](0044-email-is-a-notification-transport.md#decision): a refund of an ambiguous SMTP attempt, and a send without accounting when the budget store fails.
- [ADR 0044](0044-email-is-a-notification-transport.md#decision): Boot's `MailSenderAutoConfiguration` without a condition, which registers a sender for an empty host.
- [ADR 0044](0044-email-is-a-notification-transport.md#consequences): SMTP as a startup or readiness gate.
- [ADR 0044](0044-email-is-a-notification-transport.md#consequences): a gateway that exempts its own operator alerts from Silent Mode.
