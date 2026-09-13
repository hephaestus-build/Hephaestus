# ADR 0044: Email is a notification transport, delivered through the event publication registry

**Status:** Accepted
**Date:** 2026-09-12

## Context

Hephaestus had shipped an email subsystem once (a Postfix relay, a Thymeleaf template, a per-user
toggle) and removed it with the legacy detector. `Account.primary_email` stayed, documented as the
outbound contact for "account-delete confirmation when we add email, security alerts, GDPR
notifications", and the roadmap keeps asking for a channel: a digest for approvers, operator alerts,
credential-expiry reminders. The
[event-substrate decision](https://github.com/hephaestus-build/Hephaestus/issues/1195) had already
locked "Spring Modulith Event Publication Registry, scoped to outbound notifications only", and it
was never implemented.

Three questions decided the shape.

**Is email an integration?** No. GitHub, GitLab, Slack and Outline are providers: a connection per
workspace, credentials at rest, inbound events, an adapter absorbing a vendor's API. Email has none
of that — one instance-wide relay the operator configures once, no inbound side, and the framework
already supplies the client. Filing it under `integration/` would have forced the connection and
capability vocabulary onto something that has neither.

**Where does reliability live?** A notification is owed the moment a change commits, and the SMTP
relay is the least reliable thing in the deployment. A fire-and-forget `@Async` listener loses the
notification when the pool queue is full or the relay is down. A hand-written outbox table would
reproduce what Spring Modulith's registry already does: persist the event in the business
transaction, mark the listener's attempt, resubmit what failed.

**Which template engine?** Thymeleaf is already on the server classpath and renders escaped HTML
and plain text inside the JAR. React Email is a viable alternative for Node-based rendering, but
would introduce an additional export or rendering step here without an existing consumer for it.

## Decision

- **The `notification` module owns messages Hephaestus sends to people outside the reviewed work.**
  `notification.email` is the first transport; listeners that turn events into notifications live at
  the module root. The role checker that used to sit there moved next to the SPI it implements.
- **Every external delivery listener is an `@ApplicationModuleListener`.** `spring-modulith-starter-jdbc` provides
  the registry; `registry-trigger-annotation` restricts it to that annotation, so the existing
  `@TransactionalEventListener` fan-out keeps its semantics. Liquibase owns `event_publication`.
  Completed publications are deleted; failed ones are resubmitted every five minutes in batches of
  50. The account-deletion listener completes obsolete notifications as `EXPIRED` at the purge
  deadline. Expiration must not be a resubmission filter: Modulith limits the SQL batch before
  applying the predicate, so expired failures could permanently starve newer publications. Queued
  resubmissions are capped with the framework's `maxInFlight`; this is not a total executor limit.
  The listener suspends transactions around SMTP; its contact lookup owns a short read transaction.
  Transport failures throw only when a retry may help; every other outcome — Silent Mode, no
  verified address, an unconfigured instance, a refused mailbox — completes the publication and is counted on `notification.email.delivery`.
- **One gateway talks SMTP**, `@OutboundEgressGateway` behind Silent Mode, returning an outcome
  value instead of throwing. Every email carries MIME text and HTML alternatives, a `Message-ID`
  under the sender's domain, RFC 3834 `Auto-Submitted` and Microsoft's `X-Auto-Response-Suppress`
  headers. Logs carry the kind and message id, never the address.
- **The transport is Boot's `spring.mail.*`**, so an operator's environment variable is the property
  path in upper case. Boot's `MailSenderAutoConfiguration` is excluded: it registers a sender for an
  empty host, and the compose topology forwards unset variables as empty strings. A server-role
  configuration conditionally imports Boot's own auto-configuration for a non-blank host, retaining SSL bundles
  and the framework's sender construction instead of copying its property mapping. The sender
  identity is `hephaestus.email.*`.
- **Thymeleaf renders HTML and text from one model**; subjects live in `messages.properties`.
- **Account notifications use a provider-verified address** (`AccountContactQuery`). The admin test
  may use an explicitly supplied recipient; it carries no account-deletion information.
- **Source modules own eligibility and lifecycle; notification owns subscriptions and delivery.**
  Account-security and deletion changes publish in the mutation transaction. Product-feedback and
  connection-attention preparation uses `BEFORE_COMMIT` to publish independently durable recipient
  events in that same transaction. An asynchronous parent fan-out would duplicate children if it
  crashed after their commit but before its own completion, so there is no durable parent fan-out.
  The synchronous admin test shares the gateway but deliberately does not queue a retry.
- **Preferences belong to native accounts**, not mirrored SCM users. All optional categories start
  off. Settings use strong ETags; erasure removes owned rows even though the account becomes a
  tombstone. Exports include the typed choices, never unsubscribe tokens. Delivery rechecks current
  address, account status, permission, subscription and source eligibility through owning-module ports.
- **Survey email records are business facts, not another outbox.** An explicit action creates one
  invitation per survey/account and queues the recipient in the same transaction. Relay acceptance
  is separate from the existing in-app participation record. Pause cancels pending work; no automatic
  reactivation occurs on resume. Reminder and summary scheduling markers commit with their publications.
- **The daily digest cursor lives with its subscription.** It advances atomically with its recipient
  publication, counts retained reports only, excludes time before opt-in and bounds downtime catch-up
  to seven days. Immediate and daily delivery are mutually exclusive for one subscription.
- **Capacity reuses Bucket4j and the existing PostgreSQL store.** Optional and total attempt budgets
  are shared across replicas and reserve capacity for essential mail. An ambiguous SMTP failure is
  not refunded. Store failure is fail-closed and retryable, not a reason to send without accounting.

## Consequences

- SMTP availability does not gate startup or the readiness probe. An optional notification relay
  must not take the application offline; the admin test action verifies configuration, and the
  mail health indicator exposes outages separately.
- SMTP delivery is at-least-once, not idempotent: a crash after relay acceptance but before
  registry completion can produce duplicates. Modulith 2.1.1 staleness detection also uses original
  publication age, so recovery can overlap an active retry. Acceptance does not prove inbox delivery.
- A new notification adds an event, a listener and two templates. Its listener owns expiration and
  recipient policy; the framework supplies durability and the gateway enforces Silent Mode.
- The `event_publication` table is a framework table: its shape follows the registry's DDL and is
  excluded from the entity drift gate.
- Optional mail has a stable, random UUID capability per account/category. It can only disable that
  subscription; it is not an authentication credential. An unauthenticated form POST handles
  RFC 8058; GET never mutates and the visible link opens confirmation. HTTPS links advertise one-click;
  the relay must DKIM-sign both unsubscribe headers. Tokens stay out of events, exports and logs.
  Reverse-proxy access logs must redact this capability path; the public confirmation page omits
  credentials, referrers and error telemetry.
- Operator alerts that must bypass Silent Mode go through the declared egress-exemption allowlist,
  as the runbook already requires; the gateway does not exempt itself.
- Self-hosters bring their own relay. The code names no relay; the university relay used by the
  canonical deployment is operator configuration only.

## Sources

- [Spring Modulith event publication registry](https://docs.spring.io/spring-modulith/reference/events.html)
  and [2.1.1 resubmission implementation](https://github.com/spring-projects/spring-modulith/blob/2.1.1/spring-modulith-events/spring-modulith-events-core/src/main/java/org/springframework/modulith/events/core/DefaultEventPublicationRegistry.java).
- [Spring Boot email support](https://docs.spring.io/spring-boot/reference/io/email.html).
- [SMTP responsibility after acceptance (RFC 5321 §6.1)](https://www.rfc-editor.org/rfc/rfc5321.html#section-6.1).
- [Spring Modulith listener transaction propagation](https://github.com/spring-projects/spring-modulith/blob/2.1.1/spring-modulith-events/spring-modulith-events-api/src/main/java/org/springframework/modulith/events/ApplicationModuleListener.java).

- [One-click unsubscribe (RFC 8058)](https://www.rfc-editor.org/rfc/rfc8058).
- [Google email sender guidelines](https://support.google.com/a/answer/81126?hl=en).
