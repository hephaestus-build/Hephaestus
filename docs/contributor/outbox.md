---
title: The outbox
description: How the server carries out every effect on the outside world, and how to add a kind of effect.
page_type: reference
---

An effect on the outside world is one action in the `agent_action` table.
Email, product-feedback issues (`FILE_ISSUE`, `/publish` as `PUBLISH_REPORT`, the scrub after an erasure as `CLEANUP_ISSUE`, and **Make private** as `TRANSFER_ISSUE`), the model draft of a product-feedback report (`DRAFT_REPORT`), feedback on the reviewed work (`DELIVER_FEEDBACK`, `EDIT_FEEDBACK` and `RETRACT_FEEDBACK`) and Hephaestus's Slack posts (`SLACK_POST`) are the kinds of action in this release.
One executor carries out the actions on every server replica.
[ADR 0079](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0079-one-agent-system.md) records why there is one outbox.
The code is in `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/action/`.

## Enqueue an effect

Call `AgentActions.enqueue` with an `ActionRequest`.
Call it in the transaction that makes the change, if there is one.
Then the action exists exactly when the change commits.

| Field | Rule |
| --- | --- |
| `kind` | The `ActionKind`. Each kind has exactly one adapter. |
| `workspaceId` | The workspace of the effect. It is null only for an instance-level kind, such as an email to an account. |
| `laneKey` | Actions with the same key never run at the same time, and run in id order. Use one key for every write to one target, and enqueue them under the target's lock when their order matters. |
| `idempotencyKey` | The natural key of the effect. A second enqueue with the same key returns the first action and changes nothing. |
| `payload` | What the adapter needs, as JSON. Keep it to identifiers where the adapter can read the rest. |
| `subjectAccountId` | The account that the effect addresses. The erasure of that account deletes the action. |
| `maxAttempts` | The attempts before the action ends `FAILED` with `ATTEMPTS_EXHAUSTED`. |
| `expiresAt` | When an action that has not started is no longer worth carrying out. |

Do not call a provider for an effect yourself.
`OutboundEgressArchitectureTest` refuses a call to `ActionAdapter.execute` from any class except `ActionExecutor`.

## The lifecycle

`ActionState` holds the transitions.
The trigger `guard_agent_action` refuses every transition that `ActionState` does not allow.
`ActionStoreIntegrationTest` checks every pair of states against the trigger.

| From | To | When |
| --- | --- | --- |
| `PROPOSED` | `ADMITTED`, `CANCELLED` | The server admits or refuses an agent's proposal. |
| `ADMITTED` | `EXECUTING` | A claim takes the action. The epoch and the attempt rise by one. |
| `ADMITTED` | `CANCELLED` | The action expired before it started. |
| `EXECUTING` | `EXECUTING` | A claim takes the action again after its lease expired. The epoch and the attempt rise by one. |
| `EXECUTING` | `ADMITTED` | A proven retry or a deferral. |
| `EXECUTING` | `DONE`, `UNCERTAIN`, `FAILED`, `CANCELLED` | The attempt ended the action. |

An ended action never changes, and it keeps no payload.
Every ended action has a `stop_reason`.

## The lease and the fence

The executor claims due lane heads with `FOR UPDATE SKIP LOCKED`.
A lane head is an action with no executing action in its lane.
An admitted action also waits for an earlier action of its lane that has not started.
A reclaim does not wait, so a lane whose holder crashed does not stall.
This holds for a kind that the replica knows (see [Rows from a newer build](#rows-from-a-newer-build)).
The unique index `ux_agent_action_lane_executing` allows one executing action per lane, also when two replicas claim at the same moment.
Every time is the database clock, so replicas with different clocks agree on a lease.
There is no leader.

Every write after a claim names the claim's epoch.
A write that names an older epoch changes nothing.

Just before the provider call, the adapter calls `ActionAttempt.fence().beginWrite()`.
It sets `write_started_at` in its own short transaction.
It refuses when another claim took the action, when an erasure deleted it, or when less than 60 seconds of the lease are left.
The provider call must end within those 60 seconds.
If it refuses, the adapter must not call the provider and returns `Abandoned`.
No provider call runs inside a transaction ([Transactions and side effects](./transactions.md)).

## What an adapter returns

| Result | Use it when | Then |
| --- | --- | --- |
| `Done` | The provider confirmed the effect. | `DONE`, with the provider's reference in `readback_ref`. |
| `Retry` | The provider proved that it holds nothing, and a later attempt may succeed. | `ADMITTED` after the wait, or `FAILED` at the last attempt. The fence is cleared. |
| `Defer` | Not now, and nothing was written, such as a rate limit. | `ADMITTED` after the wait. The attempt does not count. |
| `Reread` | The effect may exist, and the provider can be read back. | Still `EXECUTING`, with its fence. The lease ends after the wait, and the next attempt reads back first. At the last attempt, `UNCERTAIN`. |
| `Uncertain` | The effect may exist. | `UNCERTAIN`. Nothing sends it again. |
| `Failed` | The provider refused it, or nobody can receive it. | `FAILED`. |
| `Cancelled` | Nothing should be written. | `CANCELLED`. |
| `Abandoned` | The fence refused. | Nothing. Another holder owns the action. |

An exception from an adapter counts as `Retry` before the fence.
After the fence, it counts as `Reread` for a kind that reads back, and as `Uncertain` for an at-most-once kind.
`ABSENT` ends an action whose readback proved that the effect does not exist and should not be made now.

## End the domain row

`ActionAdapter.ended` gets each action of its kind that ended, in the transaction that ended it.
An attempt, an expiry or the reaper can end an action, and each one calls it.
Use it to move the domain row on, such as a filing to `FILED` or `STOPPED`.
It writes to the database only and never calls a provider.

## Read back or send at most once

A provider that can find an earlier write, such as a comment with a marker, reads back.
When `ActionAttempt.writeStarted()` is true, an earlier attempt may have written.
Find that write by its marker before you write again.

A provider that cannot read back is an `atMostOnce()` kind, such as `EMAIL`.
The database refuses to claim such an action again after its write started.
When its lease expires, the executor ends it `UNCERTAIN` with `LEASE_LOST_AFTER_WRITE`.

## Email

`EmailAdapter` is the only adapter of `EMAIL`, and the only class that talks SMTP.
Each kind of email is an `EmailNotice`.
The payload holds the notice's event, not the message.
The notice prepares the message from the event at enqueue time and again before every attempt.
So a retry does not reach a person who unsubscribed or lost the role or the address in the meantime.

Each email has a stable `Message-ID` derived from the idempotency key.
The adapter retries only what the relay provably did not accept: a failed connection, a failed login, or an SMTP reply code of 4xx.
Any other failure after the message reached the transport ends `UNCERTAIN`.

## Feedback on the work

Each feedback has one lane, `feedback:<feedback id>`, and its newest action is the feedback's send.
`FeedbackSendQueue` enqueues the sends in the transaction that changes the feedback, under the feedback's row lock.
A new send cancels a send of the lane that has not started.
While a send runs, `queue` refuses a new send with 409. A caller that sees a running send leaves the correction to it.
When a send ends, its `ended` hook decides, under the feedback's lock, whether the send owes a correction, and queues it.
So a decision or a mark that commits while the send runs is never lost.

The copies keep their own write fences.
A send calls `beginWrite` before it fences a copy.
While a copy's write may have reached the provider, the send returns `Reread`, and the next attempt reads the copy back by its marker.
The `ended` hook also projects the copies of a send that ended without its own settlement.

## Product-feedback draft

A `DRAFT_REPORT` action makes one model call, not a provider write.
It is in the outbox because the outbox already owns the claim, the lease, the retry, the expiry and the lane order.
The action runs in the report's lane, `file_issue:<report>`, so it ends before the report's filing starts.
Its key is `draft:<report>:<generation>`. It has three attempts and expires ten minutes after it is enqueued.

`DraftReportAdapter` sends the call through `ProductFeedbackModelGateway.draft` to the LLM proxy in the server's JVM.
The request carries no tools, so the report's text can make the model answer but never act.
The draft writes nothing outside Hephaestus. Thus, the adapter calls no fence and reads nothing back: a repeated call costs only tokens.
A served answer is kept on the filing under the attempt's lease, and the action ends `DONE`.
Capacity refusal returns `Defer` and applies `DRAFT_DEFERRED`, so the filing enters `DRAFT_WAITING` without spending an attempt. The next claim applies `DRAFT_CLAIMED`.
A provider failure or rate limit returns `Retry`. A draft does not absorb provider throttles into another pool admission. A model that is gone or that the proxy refuses returns `Failed` with `NOT_CONFIGURED`.
The `ended` hook moves the filing to `ANSWERED`, with the kept answer only when the action is `DONE`.
[Product feedback architecture](./product-feedback.mdx#the-draft-action) describes the checks before the enqueue.

## Carry out an effect at once

`ActionExecutor.runNow` can carry out an admitted lane head with its registered adapter.
It claims and fences the action in the same way as the scheduled executor.
A lost notification leaves the committed action due for the executor of any replica.

Slack enqueues one `SLACK_POST` when the turn ends, in the transaction that stores its admitted entries.
The adapter reads those entries and posts one complete message.
[Slack metadata](https://docs.slack.dev/messaging/message-metadata/) carries the question id, or the digest id.
An attempt after an unknown write only reads the history with `include_all_metadata=true`.
Unreadable or incomplete history stays uncertain. It does not authorize a second post.

## Retention and erasure

The executor deletes an ended action 30 days after it ended, except `SLACK_POST`.
Slack actions retain their outcome and idempotency key while their question or digest entry remains unredacted.
The durable ledger change event removes the terminal action after source redaction commits.
If an action is still live then, its settlement removes it after recording any feedback receipts.
A turn notification never enqueues an action, so a late notification cannot repeat a removed post.
The entry stores only the Slack message reference needed to open a thread. Delivery state has one home in the outbox.
That is longer than any notice's expiry, so a late redelivery of the event still finds the idempotency key.

`ActionPersonDataCatalog` deletes the actions whose `subject_account_id` is the erased account.
Account purge and the instance-admin person job both reach it.
A pending action is then never carried out.
A write that already passed its fence still reaches the provider, as it would have a moment earlier.

## Observe it

The executor's metrics (`action.attempts`, `action.ended`) and the backlog gauges of `ActionBacklogSampler` are in the [Metrics](/admin/metrics#outbox-metrics), with their alerts.
A new kind gets its series and alerts without a change there: each is labelled by `kind`.
The executor's log events, such as `action.ended.uncertain`, are in the [Log records](/admin/log-records#agent-run-and-outbox-events).
Each names `action.id` and `action.kind`, never the payload.

## Rows from a newer build

During a rolling update, a newer replica can write a row that an older replica cannot decode.
The row has a kind that the older build lacks, or a known kind with a variant that its adapter lacks.
Examples are an email of a new notice, an enum value in an email's event, and a send action in a feedback's key.
The older replica does not claim, reclaim, reap or count such a row. It never ends it as failed.
The newer replica decides it.
The row still holds its lane: a supported action behind it waits until a replica that knows the row ends it.

Each adapter declares what it decodes, in `ActionAdapter.variants()`. There is no default:

- `ActionVariants.one(Payload.class)` states that the payload names no variant. Its enum components are still checked.
- `ActionVariants.tagged(tag, body, bodies)` states that a string property of the payload selects the variant, with one body record for each tag.
- `ActionVariants.keySuffix(values)` states that the last segment of the idempotency key selects the variant.

Each declaration becomes one JSON path. The claims, the reaper and the backlog gauges select only the rows that the path of their kind selects.
The path derives from the declared types, so the values that an adapter decodes have one home.
A new variant ships as a new kind, or as a new tag or suffix value that its adapter declares. A new field of an existing variant is optional.
The retention sweep reads ended rows, which have no payload, so it selects by kind only.

A read of one lane or of named keys does not filter, because a skipped row would let the caller enqueue under its generation.
A reader that decodes the row, such as the feedback send lane, fails on an unknown kind or variant instead.

## Add a kind

1. Add the value to `ActionKind`. Set `atMostOnce` and `instanceLevel`.
2. Add the value to `ck_agent_action_kind` in your branch's changelog. If the kind is at most once, add it to `guard_agent_action`. If it is instance-level, add it to `ck_agent_action_workspace`.
3. Write the adapter in the module that owns the effect. Declare what it decodes in `variants()` ([Rows from a newer build](#rows-from-a-newer-build)).
4. Keep the domain lifecycle, such as a feedback's state, on the domain row. The outbox owns only the delivery of the effect. Move the domain row in `ended`.
5. Delete the delivery code that the kind replaces, in the same pull request.

## Test it

The scheduler is off in tests.
`ActionTestSupport.drain` makes every waiting action due and runs the executor until no action is due.
