# ADR 0158: The mentor orders commitments per person and answers per request

**Status:** Accepted
**Date:** 2026-11-26
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md)

**Amends:** [ADR 0079](0079-one-agent-system.md) § Interactive and proactive work, in place of ADR 0120

**Builds on:** [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) (runs, leases and the outbox), [ADR 0151](0151-each-external-write-has-one-delivery-key-and-is-read-back-never-retried-blindly.md) (delivery identity and readback)

**Relates to:** [ADR 0121](0121-a-composer-on-a-shared-thread-writes-feedback-on-the-work-and-cannot-read-private-data.md) (the shared composer), [ADR 0139](0139-the-contact-policy-decides-whether-hephaestus-may-interrupt-a-person-now.md) (contact), [ADR 0157](0157-the-mentor-core-keeps-what-the-person-saves-and-each-answer-shows-what-it-used.md) (what the person saves)

## Context

ADR 0120 made the conversation thread the unit of order.
Each thread had one inbox, one live turn, and one queue for messages and events.
A message cancelled a queued or running background turn.
An inbox event reached the model as a user-role message with a provenance mark.

The 1.0 mentor belongs to the person, not to a workspace thread.
One person talks to Hephaestus in the app, in a Slack DM and in Slack threads.
Each place has its own transcript and its own readers.
A question can start work that ends later, and the answer goes back to where the question came from.
The thread model cannot express this.
A workspace thread is not the unit of personal work, and one queue per thread lets background work hold the question's slot.

## Decision drivers

- A question always gets an answer or a truthful failure on the route it came from.
- Stop, revocation and Forget never wait behind model work.
- Background work never starves a question, and a flood of project events never becomes a flood of messages.
- Code decides what may reach a person. A model only chooses the words.
- A crash, a retry or a restore never produces a second answer or a second post.

## Considered options

1. **Keep ADR 0120: one inbox and one turn for each thread.** Personal work then belongs to a workspace thread. A background turn shares the question's lease, so admission must cancel it inside the question's transaction.
2. **One global queue for each person.** It orders unrelated reads and model calls behind each other. One slow job blocks every answer.
3. **A workflow engine as a second control plane.** It gives durable signals and replay. It adds an operations system, and its history would hold private prompt bytes.
4. **Typed records with per-person commitment order and per-request answers (chosen).**

## Decision

Option 4.

1. **Four records, never one inbox.**
   - Provider ingress verifies the source and deduplicates its events.
   - The mentor mailbox holds typed references with a disposition and a wake time, never message text.
   - The activity view shows the person their own requests, tasks and receipts, for that audience only.
   - Requests, attempts, tasks and effects hold the work and its delivery.
2. **The person orders commitments. A request orders its answer.**
   - Transitions that change a commitment or a control lock the person's mentor row, then the request. The mentor row is the runtime's own, not the account's authority row.
   - Reads, evidence work and model calls hold no such lock and run in parallel.
   - There is no total order across the app and Slack.
3. **Each audience has its own inbox.** The key is the audience and its place: the app thread, the Slack DM, or the Slack thread root. Private words never move to another audience.
4. **A question opens a request.**
   - A new question opens a request at version 1.
   - An edit of an open request's question raises the version. Work on an older version is fenced at its next commit.
   - An edit of a settled request is recorded and changes nothing.
   - A delete fences the request and its unsent effects.
   - An edit never opens a request. A new message that asks for a different outcome supersedes the request and opens a linked successor.
5. **Work never cancels on a new question.**
   - A question steers its own request only.
   - Unrelated work keeps running or waits fairly.
   - Background work has its own slots and leases, so it never holds a question's slot.
6. **Control has reserved capacity.**
   - Stop, revocation, Forget and task callbacks are claimed by their own pass, ahead of the model queue.
   - Stop and completion compete for one terminal compare-and-set on the request.
   - Stop also fences every effect that has no write admission yet.
7. **Events are typed, and each kind has one admission rule.**

   | Event | Rule |
   | --- | --- |
   | A question or a compatible correction | Bound to its request. It is never coalesced. |
   | A project change | A discretionary candidate. It is coalesced by person, scope and trigger family, and has a deadline and a maximum lateness. |
   | A timer | An occurrence with a stable key and a misfire rule. A restart records missed occurrences and sends no backlog. |
   | A task result | Work to close on the request that asked for it. Its result and its callback commit together. |

   Questions, approvals, safety changes and outcomes are never coalesced.
   A delivery receipt, a self-post or an acknowledgement never starts a second answer.
   The coordinator wakes from a typed event and checks the current authorization before it acts.
8. **No system event speaks as the person.** An event reaches the model as a typed fact under its own provenance, never as a user-role message.
9. **Every commit checks its claim.**
   - A result or an effect commits only under one compare-and-set on:
     - the request version
     - the claim's lease epoch and token, on the database clock
     - the deadline
     - the live scope
   - No transaction spans a model or provider call.
10. **One terminal effect for each request.** It is an answer, an app continuation or a failure. A fixed receipt and the fixed retention notice have keys of their own. Each effect has a stable key and a marker derived from it, as ADR 0151 decides.
11. **The cutover and the drain.**
    - The transport chooses the producer of each new event at intake, `LEGACY` or `MENTOR`, and stores it with the event.
    - The first event of a message pins the producer for its edits and deletes.
    - A route moves to the mentor by its binding, behind a flag that is off by default.
    - A route moves only when the mentor can deliver. The write fence, the owner handoff and the live checks must be in place.
    - The legacy consumer drains its own rows and never hands one over.
    - No legacy history is replayed into the mentor, and no message is sent twice.
    - A rollback changes the producer of new events only.

### What this changes in other ADRs

- ADR 0120 is superseded. These parts stay true for the mentor:
  - typed events with IDs only
  - one output route for each attempt
  - one transaction that commits a result with its effects
  - no lock across a provider call
  - a work budget that no retry resets
- ADR 0079 § Interactive and proactive work, which ADR 0120 amended, is amended by this ADR now.

## Consequences

- Good: a question never waits behind background work, and Stop never waits behind a model call.
- Good: an edit, a Stop and a completion settle in one compare-and-set. The person gets at most one final answer.
- Good: each audience keeps its own transcript, and private words do not cross into Slack.
- Bad: four records and their states replace one inbox table, and each needs its retention, erasure and export.
- Bad: requests on different audiences of one person can finish in any order.
- Bad: the cutover runs two producers side by side until the legacy rows drain.

## Confirmation

Not enforced. The mentor runtime's units add these checks beside the code, and the review of each unit catches a missing one:

- A provider event that arrives twice admits once. A second event ID for the same message opens no second request.
- A kill at each of three points recovers with one terminal result. The points are before intake, before the receipt, and before a task result's wake.
- Stop, an edit or a revocation that races a completion leaves exactly one terminal transition.
- A bench floods discretionary project events while the person asks, stops and gets a task result.
  - The question starts within its slot bound.
  - Stop settles within its control bound.
  - No candidate reaches the person without policy.
- A restart after missed occurrences sends no backlog.

## Revisit trigger

- Queue age for questions exceeds its target at normal load.
- A workflow across services needs more than the database and the outbox can give.
- People report an answer in the wrong place or a second answer.

## Sources

- OpenClaw, [Session controller](https://docs.openclaw.ai/concepts/session-controller). Controller modes separate steering, grouping and follow-up. This gives decision 5's scoped steering and decision 7's bounded grouping.
- Temporal, [Workflow message passing](https://docs.temporal.io/encyclopedia/workflow-message-passing). A query reads state, while signals and updates change it. This gives decision 2's split between commitment transitions and parallel reads.
- Slack, [Events API](https://docs.slack.dev/apis/events-api/). Slack retries an event that is not acknowledged in time. This gives decision 11's producer choice at intake and the dedupe of decision 1.

## Already priced and declined

- Separate message turns and event turns, with yield and requeue ([ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) option 1).
- One queue for each input source, beside the conversation ([ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) option 2).
- A heartbeat loop that looks for work ([ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) option 3).
- Interrupt on every message ([ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) option 4).
- Queue only, with no steering ([ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) option 5).
