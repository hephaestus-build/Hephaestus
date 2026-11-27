# ADR 0120: A conversation thread has one inbox and runs one turn at a time

**Status:** Superseded by [ADR 0158](0158-the-mentor-orders-commitments-per-person-and-answers-per-request.md)
**Date:** 2026-11-24
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0079](0079-one-agent-system.md) § Interactive and proactive work.

**Builds on:** [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) (runs, leases and the outbox), [ADR 0079](0079-one-agent-system.md) (one sandbox for each subject)

**Relates to:** [ADR 0121](0121-a-composer-on-a-shared-thread-writes-feedback-on-the-work-and-cannot-read-private-data.md) (the audience rule and shared threads), [ADR 0122](0122-a-copy-on-the-work-that-misses-its-deadline-is-withheld.md) (the copy deadline), [ADR 0151](0151-each-external-write-has-one-delivery-key-and-is-read-back-never-retried-blindly.md) (delivery identity), [ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md) (the Slack route)

## Context

> **Code decides when Hephaestus speaks and who may read it. A model only chooses the words.**

This is the thesis of the conversation core. This ADR gives its first half. Every input to a thread is an inbox item, and code decides whether and when an item starts a turn. [ADR 0121](0121-a-composer-on-a-shared-thread-writes-feedback-on-the-work-and-cannot-read-private-data.md) gives the second half, the audience rule.

On main, a turn answers one question ([ADR 0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md)).
A digest is a separate `DIGEST` run, and review composition is a separate `REVIEW_COMPOSE` run.
Follow-ups wait behind a running turn, at most 3, and nothing steers a running turn.

Hephaestus now also speaks first.
A review result, a finished task, a due check-in or a reply on the work can each need words.
These inputs arrive at any time, also while the person types.
The real constraint is one order of text in each thread, with no lost input and no duplicate output.

The reference systems converge on one mailbox for each conversation and one turn at a time.
Their background inputs never interrupt, and a person's message steers at a tool boundary (Sources).

**Terms in this ADR.**
An **inbox event** is an inbox item that no person typed, such as a finished review, a finished task or a due schedule.
The review glossary's Event keeps its own meaning: one change to an item.
The **conversation agent** runs turns on one account's private threads. Hephaestus is not an agent.

A **task** is background work that a turn requests and that runs as its own run (decision 16).
The glossary rows for these terms and the planned meaning of Turn are proposed in #4866.

## Decision drivers

- A person's message gets an answer fast, and the person can correct a running answer.
- No input is lost, and no input is answered twice.
- Code, not the model, decides whether and when an input starts a turn.
- One mechanism serves messages, results, schedules and commands.
- Every state is a row. A lost sandbox loses nothing.

## Considered options

1. **Separate message turns and event turns, with yield and requeue.** A message ends the event turn as `YIELDED`, and the server requeues it. The requeue races a queued successor and needs a quiet interval. Steady chat can postpone background work without a bound.
2. **One queue for each input source.** A feedback lane for each person runs beside the conversation. Two writers speak for one person and need extra capacity. An answer and a feedback write about one item must reconcile in either commit order.
3. **A heartbeat loop.** A periodic turn looks for work. It spends model calls when nothing changed and collides with active turns. OpenClaw moved its heartbeat to an ordinary scheduled job, and Letta removed heartbeats.
4. **Interrupt on every message.** A new message cancels the running answer and starts again. A short correction throws away work, and partial tool calls need cleanup.
5. **Keep ADR 0082 as it is: queue only, no steering.** A correction waits for the whole answer. Events have no path into the thread, so each new input needs its own run kind.
6. **One inbox for each thread, one kind of turn, one commit rule (chosen).**

## Decision

Option 6.

1. **The thread is the unit of order.** Each thread has one inbox and one audience. A private thread also has one transcript.
   - Private threads are the main thread, a message thread and a work thread. A work thread starts at one item card.
   - Shared threads are a work place ([ADR 0121](0121-a-composer-on-a-shared-thread-writes-feedback-on-the-work-and-cannot-read-private-data.md)) and a Slack channel thread ([ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md)).
2. **Every input is an inbox item.** An item is a message or an inbox event. It carries IDs only, a dedupe key, a custody class, a due time, a reader set and a pinned route.
3. **Ingest writes the item in the transaction of its source.** A message also writes its entry at once, so the person sees it. A second insert with the same dedupe key does nothing.
4. **A start policy in code decides each item.** The table names its thread, its custody, its route, whether it starts a turn and when.
   - The model decides nothing about dispatch.
   - A gate, such as active hours or idle only, holds items, never a whole thread.
   - A workspace or a person can make the policy quieter, never louder.
5. **One live turn for each thread.** A turn is an `agent_run` of kind `TURN` on the subject `conversation:<thread id>`. Threads run in parallel.
6. **One kind of turn.** A turn that holds a message is interactive. Else it is background. The band follows that class.
   - The class decides priority, not streaming. Only an answer to the owner in the app streams.
   - A Slack answer and a channel answer post as one complete message.
7. **The claim freezes a batch.** A message is due 500 ms after the last message of its burst. It is due at most 2 s after the first.
   - One turn takes every waiting compatible item, up to the bound of its route.
   - A Slack turn takes at most 10 source keys ([ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md) decision 7). This bound applies to the claim and to steering.
   - A message past the bound stays pending for the next turn.
8. **A turn has exactly one output route.** It selects only items whose reader set and pinned route fit that route. Other items wait for their own turn. [ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md) decides the Slack routes.
9. **Collisions follow one table.**

   | Case | Behavior |
   | --- | --- |
   | A message arrives while a background turn is queued or leased. | Admission ends that turn `CANCELLED` and inserts an interactive turn, in one transaction. The LLM proxy cancels the call in flight. No attempt is charged. |
   | A compatible message arrives during an interactive turn. | It steers. After each tool batch, the runner takes it before the next model call. |
   | A message arrives while the final answer is written, or it does not fit the turn's route, actor or bound. | It waits and starts the next turn. |
   | An inbox event arrives during any turn. | It waits. Inbox events never interrupt and never join a turn in progress. |
   | The person selects Stop. | The run gets a short settlement deadline. The holder can store only complete text. It stores no effect and no task. |
   | The holder does not settle in time. | The reaper ends the run and stores no entry. Injected messages end `STOPPED` and stay visible as questions. |

   A cancel is safe because a background turn has committed nothing.
   Nothing cancels a turn on a shared thread, because no person's private message reaches it.
10. **The commit rule.** One transaction writes the turn's entries, consumes its items, and inserts its effects and tasks. Else the turn writes nothing.
    - The commit holds the current lease epoch and an unexpired lease, as main's `lockCurrent` checks.
    - Commit, Stop settlement, reap and the deadline sweep are exact complements under the same locks. Exactly one of them wins, also for a claimed item.
    - No tool call writes outside this transaction. A tool that proposes a write returns a staged draft.
11. **One lock order.** Every transaction takes source locks, then the sandbox row, then threads by key, then runs, then effects. No lock is held across a long-poll wait or a provider call.
12. **Custody.** Every ended attempt releases its claims on items that stay pending.
    - A message is never a required item. Required items come only from obligations that the system tracks with a deadline.
    - A required item ends only with its disposition, in the same transaction.
    - Each producer of a required item sets its deadline and its fixed disposition. At that deadline, the sweep applies the disposition with no model call, also to a claimed item.
    - A required item ends `CONSUMED`, `SUPERSEDED` or `DROPPED`. It never ends `EXPIRED` or `STOPPED`.
    - A newer item of the same source ends it `SUPERSEDED`. The newer item takes over the disposition, with the earlier deadline.
    - An authorization loss or a deleted source ends it `DROPPED`. The disposition records that reason and writes no message.
    - Stop never ends a required item. It releases the claim, and the item waits for its next turn or its deadline.
13. **A work budget for each item.** The LLM proxy debits each call on every claimed item at call admission. Attempts count on each item. No breaker, timer or new run resets the counts.
14. **Inbox events in the context are marked.** They reach the model as a user-role message with a provenance mark: "System events. Not the person's words."
    - The text of an inbox event cannot authorize a command or a consent. Only a person's act can.
15. **A background turn commits one message or none.** `NO_REPLY` is a normal end for optional items. A background turn has at most 12 model calls and 3 minutes.
16. **A task is background work with its own run.**
    - Only a turn's commit inserts a task (decision 10). Nothing else inserts one.
    - A task runs as its own `agent_run` in a sandbox. It reads only what every reader of its result route may read.
    - A task is `QUEUED`, then `RUNNING`. It ends exactly once: `DONE`, `FAILED` or `CANCELLED`.
    - `DONE` or `FAILED` inserts one inbox event into the requesting thread, in the same transaction. `CANCELLED` inserts no inbox event.
    - The person can cancel a queued or running task from its task status card.
    - Cancel and end are complements under the same lock. Exactly one wins.
    - Times on a task status card, such as "about 2 minutes", are targets, not promises.

    The two task kinds in 1.0. Each kind names the routes that may request it and the routes that may show its result.

    | Task | What it reads | What it returns | Routes |
    | --- | --- | --- | --- |
    | Private check | The person's own draft and the practices that it checks | A private check outcome with one reader, the person. No observation and no copy on the work. | The app or Slack may request it. The result shows only in the app. A Slack request gets a fixed acknowledgement with an app link. |
    | Research task | Read-only archives of items that the person can read | A report for the person. It takes no action. | The app or Slack may request it. The result follows the requesting route. |

### What this changes in other ADRs

- [ADR 0131](0131-a-person-has-one-conversation-in-each-workspace-and-its-answers-stream.md) supersedes ADR 0082. Its table lists which decisions of ADR 0082 this ADR changes. These are decisions 1, 7, 11, 13 and 21.
- [ADR 0079](0079-one-agent-system.md) § Interactive and proactive work says that each turn runs in band `interactive`. A turn now runs in band `interactive` or `background`.

## Consequences

- Good: one path serves a message, a result, a schedule and a command.
- Good: a correction reaches a running answer within one tool batch.
- Good: a required item always ends, also when the model provider is down.
- Good: every commit is all or nothing, so a retry never repeats an effect.
- Good: a task returns through the same inbox as every other input, and the person can cancel it.
- Bad: a message that cancels a background turn wastes the model calls that the turn spent.
- Bad: steady chat delays optional background messages. Required items still meet their deadlines.
- Bad: a steered answer can mix two questions in one reply.
- Bad: each input adds an `inbox_item` row. The upgrade must map queued turns and `DIGEST` runs to items.
- Bad: each task is one more sandbox run beside the conversation. Its times are targets, so a busy instance can miss them.

## Confirmation

Not enforced. The implementing issues add the checks below, and review of each implementing pull request catches a missing one. They are:

- CommitAgainstCancelTest fails when a won cancel leaves an executable action, or a won commit loses its effects.
- ClaimAgainstAdmitTest fails when a claim and a message admission on two replicas deadlock, or both win.
- StopSettlementAfterDeadlineTest fails when a holder settles after its Stop deadline.
- SilentCommitReleasesCustodyTest fails when a `NO_REPLY` commit leaves a required item claimed.
- RequiredDispositionWithoutModelTest fails when a required kind misses its disposition while the model breaker is open.
- DebitAtCallAdmissionTest fails when a crash after a model call skips the debit on a claimed item.
- GateHoldsOnlyItsItemsTest fails when a gate on one item delays another item's due time.
- RequiredItemEndsWithDispositionTest fails when a required item ends `EXPIRED` or `STOPPED`, or ends without its disposition.
- TaskCancelAgainstEndTest fails when a cancel and a task end both win, or an end commits without its inbox event.
- TaskReadsRouteScopeTest fails when a task reads a source that a reader of its result route may not read.

## Revisit trigger

- Over four weeks, messages cancel more than a quarter of background turns.
- More than 1 % of required items end by their time-based disposition.
- A turn must change a shared place on a person's behalf without a typed command.

## Sources

- OpenClaw, [Queue](https://docs.openclaw.ai/concepts/queue). Inputs "enter the same session controller mailbox", "running turns are never preempted", and steering applies "after each batch settles". This gives one inbox, no interrupt by events, and steering at the tool boundary.
- LangChain, [Agent Server](https://docs.langchain.com/langsmith/agent-server). "The queue enforces that at most 1 run can be executed for a given thread at one time." This gives one live turn for each thread on a leased run queue.
- Anthropic, [Streaming input](https://code.claude.com/docs/en/agent-sdk/streaming-vs-single-mode). "A message you send during tool calls reaches Claude after those tool calls finish." If the turn ends first, a new turn starts. This gives decision 9's steering and follow-up rows.
