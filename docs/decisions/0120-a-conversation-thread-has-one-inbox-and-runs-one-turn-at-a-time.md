# ADR 0120: A conversation thread has one inbox and runs one turn at a time

**Status:** Proposed
**Date:** 2026-11-23
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md) decisions 1, 7, 11, 13 and 21. [ADR 0079](0079-one-agent-system.md) § Interactive and proactive work.

**Builds on:** [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) (runs, leases and the outbox), [ADR 0079](0079-one-agent-system.md) (one sandbox for each subject)

**Relates to:** [ADR 0121](0121-a-composer-on-a-shared-thread-writes-feedback-on-the-work-and-cannot-read-private-data.md) (the audience rule and shared threads), [ADR 0122](0122-a-copy-on-the-work-that-misses-its-deadline-is-withheld.md) (the copy deadline and delivery identity), [ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md) (the Slack route)

## Context

On main, a turn answers one question ([ADR 0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md)).
A digest is a separate `DIGEST` run, and review composition is a separate `REVIEW_COMPOSE` run.
Follow-ups wait behind a running turn, at most 3, and nothing steers a running turn.

Hephaestus now also speaks first.
A review result, a finished task, a due check-in or a reply on the work can each need words.
These inputs arrive at any time, also while the person types.
The real constraint is one order of text in each thread, with no lost input and no duplicate output.

An earlier internal design gave messages and these inputs two kinds of turn.
A message made a running background turn yield, and the server requeued it.
The requeue raced a queued successor, a quiet interval limited restart churn, and steady chat could postpone background work without a bound.
That design had 31 concepts on the message and event path.

The reference systems converge on one mailbox for each conversation and one turn at a time.
Their background inputs never interrupt, and a person's message steers at a tool boundary (Sources).

**Terms in this ADR.**
An **event** is an inbox item that no person typed, such as a finished review, a finished task or a due schedule.
It is wider than the review glossary's Event, which is one change to an item.
The **conversation agent** is the agent that runs turns on one account's private threads. Hephaestus is not an agent.
When this ADR is accepted, the two glossaries take these terms and the changed meaning of Turn.

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
2. **Every input is an inbox item.** An item is a message or an event. It carries IDs only, a dedupe key, a custody class, a due time, a reader set and a pinned route.
3. **Ingest writes the item in the transaction of its source.** A message also writes its entry at once, so the person sees it. A second insert with the same dedupe key does nothing.
4. **A start policy in code decides each item.** The table names its thread, its custody, its route, whether it starts a turn and when.
   - The model decides nothing about dispatch.
   - A gate, such as active hours or idle only, holds items, never a whole thread.
   - A workspace or a person can make the policy quieter, never louder.
5. **One live turn for each thread.** A turn is an `agent_run` of kind `TURN` on the subject `conversation:<thread id>`. Threads run in parallel.
6. **One kind of turn.** A turn that holds a message is interactive. Else it is background. The band follows that class.
7. **The claim freezes a batch.** A message is due 500 ms after the last message of its burst. It is due at most 2 s after the first. One turn takes every waiting compatible item.
8. **A turn has exactly one output route.** It selects only items whose reader set and pinned route fit that route. Other items wait for their own turn. [ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md) decides the Slack routes.
9. **Collisions follow one table.**

   | Case | Behavior |
   | --- | --- |
   | A message arrives while a background turn is queued or leased. | Admission ends that turn `CANCELLED` and inserts an interactive turn, in one transaction. The model proxy cancels the call in flight. No attempt is charged. |
   | A compatible message arrives during an interactive turn. | It steers. After each tool batch, the runner takes it before the next model call. |
   | A message arrives while the final answer is written, or it does not fit the turn's route or actor. | It waits and starts the next turn. |
   | An event arrives during any turn. | It waits. Events never interrupt and never join a turn in progress. |
   | The person selects Stop. | The run gets a short settlement deadline. The holder can store only complete text. It stores no effect and no task. |
   | The holder does not settle in time. | The reaper ends the run and stores no entry. Injected messages end `STOPPED` and stay visible as questions. |

   A cancel is safe because a background turn has committed nothing.
   Nothing cancels a turn on a shared thread, because no person's private message reaches it.
10. **The commit rule.** One transaction writes the turn's entries, consumes its items, and inserts its effects and tasks. Else the turn writes nothing.
    - The commit holds the current lease epoch and an unexpired lease, as main's `lockCurrent` checks.
    - Commit, Stop settlement and reap are exact complements under the same locks. Exactly one of them wins.
    - No tool call writes outside this transaction. A tool that proposes a write returns a staged draft.
11. **One lock order.** Every transaction takes source locks, then the sandbox row, then threads by key, then runs, then effects. No lock is held across a long-poll wait or a provider call.
12. **Custody.** Every ended attempt releases its claims on items that stay pending.
    - A required item ends only with its disposition, in the same transaction.
    - Each required kind has a deadline. At that deadline, the sweep applies a fixed disposition with no model call.
13. **A work budget for each item.** The model proxy debits each call on every claimed item at call admission. Attempts count on each item. No breaker, timer or new run resets the counts.
14. **Events in the context are marked.** An events block is a user-role message with a provenance mark: "System events. Not the person's words."
    - Event text cannot authorize a command or a consent. Only a person's act can.
15. **A background turn commits one message or none.** `NO_REPLY` is a normal end for optional items. A background turn has at most 12 model calls and 3 minutes.

### What this changes in other ADRs

- [ADR 0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md) decision 1 says "no … steer". Steering returns, only at the tool-result boundary and only for compatible input. The rest of decision 1 stays.
- ADR 0082 decision 7 (Stop) stays. Stop now settles within a deadline, and messages injected into a stopped turn end `STOPPED`.
- ADR 0082 decision 11 changes. The inbox item's dedupe key takes the role of `request_id`. The limit of 3 follow-ups goes, because one turn takes every waiting compatible message.
- ADR 0082 decision 13 ("A turn stores its entries once, at its end") becomes the commit rule of decision 10. The commit now also consumes items and inserts effects and tasks.
- ADR 0082 decision 21 changes. A digest or a nudge is a scheduled inbox item on the main thread, and a background turn writes it. The `DIGEST` run kind goes. Admission of its claims stays.
- [ADR 0079](0079-one-agent-system.md) § Interactive and proactive work says that each turn runs in band `interactive`. A turn now runs in band `interactive` or `background`.

## Consequences

- Good: one path serves a message, a result, a schedule and a command. The concept count on that path drops from 31 to 23.
- Good: a correction reaches a running answer within one tool batch.
- Good: a required item always ends, also when the model provider is down.
- Good: every commit is all or nothing, so a retry never repeats an effect.
- Bad: a message that cancels a background turn wastes the model calls that the turn spent.
- Bad: steady chat delays optional background messages. Required items still meet their deadlines.
- Bad: a steered answer can mix two questions in one reply.
- Bad: each input adds an `inbox_item` row. The upgrade must map queued turns and `DIGEST` runs to items.

## Confirmation

Not enforced yet. The implementation adds these tests:

- `CommitAgainstCancelTest` fails when a won cancel leaves an executable action, or a won commit loses its effects.
- `ClaimAgainstAdmitTest` fails when a claim and a message admission on two replicas deadlock, or both win.
- `StopSettlementAfterDeadlineTest` fails when a holder settles after its Stop deadline.
- `SilentCommitReleasesCustodyTest` fails when a `NO_REPLY` commit leaves a required item claimed.
- `RequiredDispositionWithoutModelTest` fails when a required kind misses its disposition while the model breaker is open.
- `DebitAtCallAdmissionTest` fails when a crash after a model call skips the debit on a claimed item.
- `GateHoldsOnlyItsItemsTest` fails when a gate on one item delays another item's due time.

## Revisit trigger

- Over four weeks, messages cancel more than a quarter of background turns.
- More than 1 % of required items end by their time-based disposition.
- A turn must change a shared place on a person's behalf without a typed command.

## Sources

- OpenClaw, [Queue](https://docs.openclaw.ai/concepts/queue). Inputs "enter the same session controller mailbox", "running turns are never preempted", and steering applies "after each batch settles". This gives one inbox, no interrupt by events, and steering at the tool boundary.
- LangChain, [Agent Server](https://docs.langchain.com/langsmith/agent-server). "The queue enforces that at most 1 run can be executed for a given thread at one time." This gives one live turn for each thread on a leased run queue.
- Anthropic, [Streaming input](https://code.claude.com/docs/en/agent-sdk/streaming-vs-single-mode). "A message you send during tool calls reaches Claude after those tool calls finish." If the turn ends first, a new turn starts. This gives decision 9's steering and follow-up rows.
