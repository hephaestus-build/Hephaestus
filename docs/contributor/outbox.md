---
title: The outbox
description: How the server carries out every effect on the outside world, and how to add a kind of effect.
page_type: reference
---

An effect on the outside world is one action in the `agent_action` table.
Email, product-feedback issues (`FILE_ISSUE`, `/publish` as `PUBLISH_REPORT`, the scrub after an erasure as `CLEANUP_ISSUE`, and **Make private** as `TRANSFER_ISSUE`), feedback on the reviewed work (`DELIVER_FEEDBACK`, `EDIT_FEEDBACK` and `RETRACT_FEEDBACK`) and Hephaestus's Slack posts (`SLACK_POST`) are the kinds of action in this release.
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

## Carry out an effect at once

A caller that holds what only it knows, such as the stream of a Slack reply, can own an effect.
It creates the action with `AgentActions.enqueueNew` and a delay, and carries it out with `ActionExecutor.runNow` and its own adapter.
If the caller dies first, the executor of any server takes the action when the delay ends, with the kind's own adapter.
That adapter does not have what the caller held, so it reads the provider back first.
The Slack reply works like this ([Slack replies](./unified-pi-runtime.mdx)).

## Retention and erasure

The executor deletes an ended action 30 days after it ended.
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

## Add a kind

1. Add the value to `ActionKind`. Set `atMostOnce` and `instanceLevel`.
2. Add the value to `ck_agent_action_kind` in your branch's changelog. If the kind is at most once, add it to `guard_agent_action`. If it is instance-level, add it to `ck_agent_action_workspace`.
3. Write the adapter in the module that owns the effect.
4. Keep the domain lifecycle, such as a feedback's state, on the domain row. The outbox owns only the delivery of the effect. Move the domain row in `ended`.
5. Delete the delivery code that the kind replaces, in the same pull request.

## Test it

The scheduler is off in tests.
`ActionTestSupport.drain` makes every waiting action due and runs the executor until no action is due.
