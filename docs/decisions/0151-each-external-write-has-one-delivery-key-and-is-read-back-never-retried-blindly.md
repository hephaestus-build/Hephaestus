# ADR 0151: Each external write has one delivery key, and is read back, never retried blindly

**Status:** Accepted
**Date:** 2026-11-24
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) decision 10 (each action gains a `delivery_key`)

**Builds on:** [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) (runs, leases and the outbox), [ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) (the commit rule)

**Relates to:**

- [ADR 0098](0098-each-email-is-one-outbox-action-and-is-sent-at-most-once.md) (email is sent at most once)
- [ADR 0116](0116-a-push-is-one-web-push-message-from-the-instance.md) (a push is sent at most once)
- [ADR 0122](0122-a-copy-on-the-work-that-misses-its-deadline-is-withheld.md) (the copy deadline)
- [ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md) (the Slack delivery keys)

## Context

With the conversation core, Hephaestus writes to other systems from many places.
Examples are a copy on the work, a reply on the work, a retract, a Slack message and a card update in Slack.
A write to a provider can end with no answer.
GitHub and GitLab comments take no idempotency key, so a blind retry can post twice and notify everyone twice.
After a database restore, the outbox can hold writes that the provider already has.

Email and Web Push have no readback.
[ADR 0098](0098-each-email-is-one-outbox-action-and-is-sent-at-most-once.md) and [ADR 0116](0116-a-push-is-one-web-push-message-from-the-instance.md) already send them at most once: an attempt whose write may have started ends `UNCERTAIN` and never runs again.

The real constraint is that no external write repeats, also after a crash, a restore or a timeout.

## Decision drivers

- No external write repeats, also after a crash, a restore or a timeout.
- A refusal or a failure before the write is still retried.
- An uncertain write never blocks the target for good.
- Internal row IDs never leave the server.

## Considered options

1. **Retry an uncertain write.** A timeout can hide a write that succeeded. A retry then posts a second copy that notifies everyone.
2. **The outbox's internal idempotency key only.** It stops a second send from the same row. It cannot find a write that the provider accepted before a crash or a restore.
3. **One delivery key for each write, a marker at the provider, and readback where the transport has it (chosen).**

## Decision

Option 3.

1. **One delivery key for each external write.** Each operation has one `delivery_key` from source facts, unique in the workspace.
   - The external marker carries it. Internal row IDs never appear in a marker.
   - Replay, readback and restore all use this key.
   - A copy on the work keys on its provider target, account, destination and operation.
     It also keys on the problem set that it must state, its generation and the part of a multi-part write.
   - A reply keys on the native reply. A retract keys on the native copy. [ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md) gives the Slack keys.
2. **A started write is never retried blindly.**
   - One transaction checks the create predicate of the write and marks the action and each of its native parts started.
   - A failure before that transaction is retried. After it, only readback decides.
   - An ordering key moves past an uncertain write only after proof of presence or proof of non-acceptance.
   - An explicit reconciliation by the person or an operator also moves it. A refusal, such as a rate-limit answer, proves non-acceptance.
   - A timeout, an incomplete page or a missing marker proves nothing.
3. **Readback depends on the operation and the transport.** Each operation names its readback proof. A create proves its marker, an edit its expected revision and a delete an authorized absence. A result that readback cannot prove ends in reconciliation, never in a retry.

   | Transport | Marker readback | An uncertain write |
   | --- | --- | --- |
   | GitHub and GitLab | Yes. The marker is in the comment or review. | Readback decides. |
   | Slack posts in history | Yes. The marker is in the message metadata ([ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md)). | Readback decides. |
   | Slack ephemeral membership refusals | No. The server commits the reply receipt before it posts the refusal. | A replay skips the reply. |
   | Email | No | It ends `UNCERTAIN` and never runs again ([ADR 0098](0098-each-email-is-one-outbox-action-and-is-sent-at-most-once.md) decision 5). |
   | Web Push | No | It ends `UNCERTAIN` and never runs again ([ADR 0116](0116-a-push-is-one-web-push-message-from-the-instance.md) decision 2). |

4. **Restore.** Egress stays held until restore reads the markers at each provider with readback. Restore rebuilds the generations and the Slack coverage from them. An effect whose identity or absence it cannot prove is suppressed and listed.

### What this changes in other ADRs

- [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) decision 10 stays. Each action keeps its internal idempotency key, and it gains the `delivery_key` of decision 1.
- [ADR 0098](0098-each-email-is-one-outbox-action-and-is-sent-at-most-once.md) and [ADR 0116](0116-a-push-is-one-web-push-message-from-the-instance.md) stay. Email and push keep their at-most-once rule, and they get a `delivery_key` too.

## Consequences

- Good: no write repeats on a provider with readback after a crash, a restore or a timeout.
- Good: a restore never posts a second copy that the team already read.
- Bad: an uncertain write blocks newer writes to the same target until readback or reconciliation.
- Bad: an uncertain email or push can be lost. No flow depends on either as its only record.
- Bad: each provider adapter must write and read a marker.

## Confirmation

Not enforced. The implementing issues add the checks below, and review of each implementing pull request catches a missing one. They are:

- FirstWritePredicatePerOperationTest fails when an operation uses another operation's predicate.
- CrashBetweenStartAndCopyFenceTest fails when a crash leaves the action or one of its parts started alone.
- UncertainLaneWaitsForProofTest fails when a newer generation passes an uncertain write without proof.
- RestoreTwoGenerationsTest fails when a restore reuses a marker that exists.
- NoReadbackTransportAtMostOnceTest fails when an uncertain email or push runs again.

## Revisit trigger

- A provider adds an idempotency key for comments or reviews. Then a started write can retry with that key.
- Readback leaves more than 1 % of writes to a target uncertain over four weeks.

## Sources

- Brandur Leach, [Implementing Stripe-like idempotency keys in Postgres](https://brandur.org/idempotency-keys). A retry is safe only when the remote call "is an idempotent operation or it supports an idempotency key". Provider comments do not, so decision 2 reads back instead.
- Chris Richardson, [Transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html). The message relay "might publish a message more than once". Decisions 1 and 2 give each write one key and read back, so a repeat never reaches the provider twice.
