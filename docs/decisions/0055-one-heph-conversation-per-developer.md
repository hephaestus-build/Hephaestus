# ADR 0055: One Heph conversation per developer, on a stateless shared Heph host

**Status:** Accepted
**Date:** 2026-10-09
**Authors:** Felix T.J. Dietrich

**Amends:**

- [ADR 0005](0005-two-role-runtime-via-conditional-on-property.md), [ADR 0006](0006-llm-proxy-on-coordinator-trust-model.md), [ADR 0007](0007-sandbox-spi-shape.md), [ADR 0008](0008-webhook-runtime-role.md).
- [ADR 0009](0009-worker-runtime-substrate-wss-control-channel.md), [ADR 0026](0026-per-purpose-agent-bindings-and-llm-governance.md), [ADR 0036](0036-agent-runtime-runs-on-node-24.md), [ADR 0041](0041-compose-1x-kubernetes-2.md).

**Builds on:**

- [ADR 0046](0046-heph-follows-its-model-not-a-workspace-switch.md): the precedent for a drop in the same release.
- [ADR 0045](0045-activity-counts-work-and-never-ranks-people.md): no score, rank, or streak.

## Context

Before this decision, a developer had many Heph chats in the app and one more chat for each top-level Slack direct message.
Each chat had its own Pi session.
A per-developer sandbox container on a worker ran the session.
The server kept the session as one blob in `chat_thread.session_jsonl`.
It kept a second copy of the visible messages in `chat_message.parts`.

This design caused these problems:

- One sandbox held one session at a time. A second question got "Heph is busy".
- The sandbox was warm or cold. The server prepared it in advance and kept it warm for 15 minutes.
- The web stream was bound to one server replica. Traefik pinned workspace traffic with a sticky cookie.
- A stream that broke could not resume. A crash between the two copies left them different.
- Slack threads appeared as separate chats in the app. The only proactive message was a count-only Slack reminder.

Pi Durable (`@earendil-works/pi-durable`) was released with Pi 1.0 on 2026-10-01.
It has the concepts that this problem needs: an append-only conversation, idempotent submissions, forks, follow-up queues, and background compaction.
We tested version 1.1.0 by hand before this decision.

## Decision drivers

- A developer has one place to talk to Heph. The app shows all of it, on every page.
- Feedback that Heph shows is delivered exactly once, on the surface where Heph showed it.
- A second question never fails because Heph is busy. Questions in different Heph threads run at the same time.
- No state lives only in a process. Any worker can run any turn from rows in PostgreSQL.
- Erasure, export, and tenancy work on rows that Hephaestus owns, with SQL that Hephaestus writes.
- Heph knows when and where each message was written, and what changed since then.

## Considered options

Product model:

1. **Keep many chats in the app and one chat per Slack message.** Rejected. Context splits across chats, and Slack threads leak into the app list.
2. **One conversation per developer per workspace. Threads fork it at one message.** Chosen.
3. **One conversation with no Heph threads.** Rejected. A Slack thread reply needs a home, and a side question must not move the main conversation.

Runtime:

1. **Keep a sandbox per developer, and fix the lock.** Rejected. The sandbox stays stateful, needs affinity, and needs warm capacity.
2. **Run Heph on Pi Durable, with a Postgres `Storage` adapter.** Rejected for now. § Pi Durable gives the reasons.
3. **One stateless Heph host per worker. A turn rebuilds its Pi session from ledger rows.** Chosen.

Compaction:

1. **Compact after the answer.** Rejected. It needs a separate "final" state for delivery and stop, and entries that stream during the turn.
2. **Compact before the prompt, in the turn.** Chosen.

Web client:

1. **AI SDK UI (`useChat` with a custom transport over the live stream).** Rejected.
   A `Chat` owns one `messages` array, and only its own requests stream into it.
   Every other writer must go through `setMessages`, gated against the engine's own write.
   Such writers are a Slack message, a digest, another tab and a retried attempt.
   Since `ai` 7.0.138, the engine replaces a streaming answer by its id.
   Thus, an external append no longer duplicates it.
   But the protocol is still delta-only. A retried attempt keeps the failed attempt's text, and `stop` is a client abort.
   Resume knows only the chat id, and persistence has no paging ([transport](https://ai-sdk.dev/docs/ai-sdk-ui/transport), [resume](https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-resume-streams)).
   Our cache already does all of this in fewer lines, with one writer.
   AI Elements and Streamdown have no React Native support.
   v0's own Platform API streams snapshots plus patches, the shape of our ledger with a snapshot and server-sent events.
2. **TanStack Query pages plus one `EventSource` that writes into the same cache.** Chosen.

Slack egress:

1. **Vercel Chat SDK with its Slack adapter, in a TypeScript service.** Rejected.
   The Slack adapter is beta.
   Its Postgres state tables are created at run time, outside Liquibase and outside workspace scoping.
   It also needs a second Slack ingress beside the Java webhook role ([Chat SDK](https://chat-sdk.dev/)).
2. **The Java Slack projector reads the ledger, and records each post as intent, effect and outcome.** Chosen.
3. **Slack agent sessions with the native stop button in threads.** Rejected.
   A stop event names no turn when no stream exists yet.
   Binding it needed event receipts, session owners and a clock-skew rule.
   Threads use 👀 as the working signal, and Stop is in the app.

Weekly digest:

1. **A model writes a weekly summary.** Rejected. It is a new claim about a person, outside admission, approval, and withdrawal.
2. **Java writes a digest from facts. A pointer with no content tells the developer that it is ready.** Chosen.

## Decision

### One conversation, Heph threads, and the surface rule

Each developer has one Heph conversation per workspace.
The app timeline and the floating Heph panel show all of it.
The top level of the Slack direct message shows the questions asked there and their answers.

A Heph thread is one level deep.
It starts at a top-level message: a question, an answer, or a weekly digest.
Each message has at most one Heph thread.
A Heph thread is a fork of the conversation at its parent message.
It never merges back.

The app shows every Heph thread.
A Heph thread started in Slack also appears in Slack, and one started in the app stays in the app.

The answer appears where the developer asked. The app shows everything.

- A question asked in Slack gets its answer in Slack. The app also shows both, with a Slack marker.
- A question asked in the app is never posted to Slack.
- In Slack, Heph names app content explicitly, because the reader cannot see it there.
- A Slack question gets the 👀 reaction while Heph works.
  At the top level, one reply follows.
  In a Slack thread, the answer streams and ends with feedback buttons.
- A developer stops a turn in the app only.
  Heph does not use Slack's agent-session status or its stop event.
  Both belong to the Slack thread, not to one turn.

The app offers Helpful and Not helpful on each Heph answer.
Ratings come from these buttons only. Heph's own 👀 reaction is its working signal, and Hephaestus does not read Slack reactions.
New chat, the chat list, regenerate, edit-and-resend, Start fresh, and steer are removed.

The actor rule for one conversation: the server finds the main conversation by any member actor of the account.
It creates the conversation under the selected actor.
A write from another selected actor moves `user_id` of the conversation and its Heph threads.

### The ledger

Three workspace-scoped tables hold Heph's state:

- `mentor_conversation`: a main conversation or a Heph thread, with the Slack team, user, and channel of the direct message.
- `mentor_entry`: each question, answer, tool result, system entry, summary, and weekly digest. A Pi entry is stored verbatim in `payload`.
- `mentor_turn`: one question's run, with its status, lease, partial answer, and usage.

These concepts come from Pi Durable:

- A client `request_id` makes each submission idempotent.
- A question for a busy conversation waits as a follow-up. A conversation holds a limited number of follow-ups.
- The server commits a row before any surface shows it.
- The partial answer is one whole snapshot, sent at a limited rate.
- The live protocol sends a snapshot first, then events. `NOTIFY` carries ids, and a turn's status and surface. It never carries content.
- A Slack post goes from intent to effect to outcome: `PENDING`, then `DELIVERED`, `UNCERTAIN`, or `FAILED`.
  The question carries the state of the whole reply, and the server that claims it projects the turn.
  A reconciler settles a reply that no server settled. It never posts a streamed reply again.
  [Heph runtime](../contributor/unified-pi-runtime.mdx#slack-replies) gives the rules.
- A turn that loses its lease runs once more with the same key. A second loss fails it.
  A stopped turn that loses its lease stays `STOPPED`.
  The host sends the entries of a turn once, at its end.
  Thus, a lost attempt stores nothing, and its retry starts clean.
- The usage of a turn is on the turn row.

Stop has two forms.
A running turn becomes `STOPPED` and keeps Pi's cut-off answer.
A queued turn becomes `STOPPED` and keeps the question with no answer.

Feedback that Heph shows through `link_observation` becomes `DELIVERED` only when the surface shows it.
In the app, that is the end of the turn as `DONE` or `STOPPED`.
In Slack, it is a post or a stream that shows the feedback's text.
At most one piece of feedback becomes `DELIVERED` for each turn.
`feedback_placement.mentor_entry_id` records the message that showed the feedback.
[Heph runtime](../contributor/unified-pi-runtime.mdx#delivery-of-shown-feedback) is the home of the delivery rule.

### The Heph host

Each worker runs one long-lived Heph host container from the agent image.
The worker starts it on the first turn that the worker claims.
The host runs many turns at once, one Pi `AgentSession` for each turn.
It has three tools: `fetch_context`, `link_observation`, and `search_conversation`.
It has no shell and no file tools.

A worker claims a queued turn when PostgreSQL notifies it.
It sends `start` and `stop` commands to the host over one WebSocket that the host opens.
The host reads the turn's context and reports the partial answer over HTTP.
At the end of the turn, it sends all Pi entries that the turn appended in one request.
It uses a turn-scoped `TurnJwt` for these calls and for model calls through the worker's LLM proxy.
The server role never talks to the host.

The worker pings the WebSocket.
A host that stops answering the pings is gone, and the worker replaces it.
[Heph runtime](../contributor/unified-pi-runtime.mdx#the-heph-host) gives the lifecycle of the host.

Nothing about a conversation outlives a turn in the host.
Any host can run the next turn, because the turn rebuilds its session from rows.
Thus, no affinity, warm sandbox, or sticky cookie remains.

### Context model

The ledger is the memory. A stateless loop rebuilds the request on each turn.
This model follows Anthropic Managed Agents and the working set of Pi Durable.
Pi's defaults apply first.
We add a setting only when an evaluation shows a loss.

Each request has four parts:

1. The fixed system prompt.
2. The fixed tools.
3. The path, rebuilt from rows. It starts at the newest summary at or before the leaf.
4. A transient situation message at the end. Hephaestus never stores it.

The path renders each question with its time and surface, for example `[Thu 2026-10-08 09:12 Europe/Berlin · Slack]`.
It marks a stopped answer and a failed turn.
It leaves out erased content. A failed attempt stores nothing.
A Heph thread sees the main conversation only up to its parent, and the summary at or before that parent.

The situation message gives the time now, the surface, and the gap since the last message.
It also gives the open Heph threads, the page of the floating panel, and the current evidence.

Context management:

- **Tool results.** When a turn settles, Pi `context_edit` entries replace older `fetch_context` results with a reference.
  Only the latest turn's results stay verbatim. Heph can fetch again.
- **Compaction.** Pi's own compaction, with its default prompt.
  It runs before the prompt of a turn above the working budget ([Heph runtime](../contributor/unified-pi-runtime.mdx#compaction)).
  The host compacts in the turn, so follow-ups wait and the summary counts against that turn.
  The end of the turn stores the summary.
  Pi's check between tool batches stays as the fallback.
- **Search.** `search_conversation` is a PostgreSQL full-text search over the conversation and its Heph threads.
  It returns entry ids that Heph can cite.
- **No separate memory.** Heph writes no notes and no branch summaries.

The session id is the conversation id.
Thus, each Heph thread has its own prompt-cache key.

### Pi Durable is not adopted

We do not install Pi Durable now, for Heph or for practice review.
Each of these reasons is sufficient alone:

- Its `Storage` contract is experimental. Patch releases 1.0.3 and 1.0.4 changed it.
- It has no supported path for a writer that lost ownership ([#10534](https://github.com/earendil-works/pi/issues/10534), closed without a fix).
- Pi coding-agent extensions and compaction hooks do not run under its `Harness` ([#10386](https://github.com/earendil-works/pi/issues/10386), open).
- Erasure is possible only with raw SQL on its private schema while the harness is closed.

A Postgres `Storage` adapter needed about the same code as the ledger, plus one more stateful service.
The ledger has the same shape as Pi Durable's records, so a later switch is an adapter, not a migration.

We revisit this decision when all four of these triggers are true:

1. The `Storage` contract is unchanged across two consecutive minor releases, or upstream ships a Postgres storage.
2. #10386 is closed, with hook parity under `Harness`.
3. Pi Durable has a supported error for a writer that lost ownership.
4. Heph gains long-running work with side effects, for example background subagents or approvals in channels.

Then we implement its `Storage` over the `mentor_*` tables.

Pi Durable concepts that we do not adopt: documents, the task tree, memos, the hook registry, and replay flags.

### Weekly digest

The weekly digest is opt-in and off by default.
The developer turns it on once for the account. No admin switch exists.

Each Monday from 09:00 to 21:00 in the developer's time zone, the server writes one `DIGEST` entry for the past week.
It writes one only in a workspace where Heph can answer the developer. After 21:00, it skips the week.

Java composes the digest from facts. No model writes it.
It lists delivered feedback as links, changes on the Practice profile, and the number of unread feedback cards.
It shows no score, rank, or streak (ADR 0045).
A quiet week writes nothing.

The digest is a pointer, not a delivery ([practice review pipeline](../contributor/practice-review-pipeline.mdx#channels-are-distinct-interventions)).
It lists feedback that reached the developer in the week, also when a newer card replaced it later in that week or after.
It leaves out feedback that is awaiting approval, withheld before delivery, failed, or unconfirmed, and prepared `IN_CHAT` feedback.
The app renders it at read time.
Thus, feedback whose evidence is invalidated disappears from it, and withdrawn feedback shows only its practice.

A Slack note says that the digest is ready.
When the developer has no Slack identity, an email says it instead.
When neither reaches the developer, the digest is only in the conversation.
The note has no counts, no practice names, and no feedback text.
A reply in the digest's Heph thread is an ordinary Heph turn.
The digest replaces the count-only Slack reminder.

### Exception: drop the old tables in the same release

`docs/contributor/database-migration.mdx` requires two releases to drop a table: stop using it, then drop it.
The rule keeps a rolling upgrade safe, when old and new code run at the same time.

This release drops `chat_thread`, `chat_message`, `chat_message_vote`, and `mentor_slack_thread` in the same changelog that creates the ledger.
The same changelog also changes two released columns:

- It renames `feedback_placement.chat_message_id` to `mentor_entry_id` and clears its values.
- It drops `workspace_agent_binding.allow_internet`.

This is an exception, for these reasons:

- Hephaestus is before 1.0.
- We accept the total loss of old Heph conversations. The old blobs do not map to the new ledger.
- ADR 0046 dropped a column in the same release, after the upgrade moved its value.

The migration note tells operators to stop every role before the upgrade.
Thus, no old code reads a dropped table or column.
This exception does not change the rule for other tables and columns.

## Consequences

- The upgrade deletes all earlier Heph conversations. Slack keeps its own copy of the direct messages.
- Operators reinstall the Slack app for `reactions:write`.
- The Traefik sticky cookie, the interactive sandbox, its configuration keys, and its metrics are removed.
- `hephaestus.worker.capacity.mentor-max` gets a new meaning ([Runtime roles](../admin/runtime-roles.mdx#tunables)).
- The worker control protocol carries no mentor frames.
- The agent image runtime contract is 5. The image ships `heph-host.ts`.
- A Heph turn that a worker loses runs again on another worker. A tool call can then run twice. All three tools are read-only.
- One turn delivers at most one piece of feedback. Under Silent Mode, it suppresses at most one more piece.
  Other feedback that the answer showed stays `PREPARED` until a later turn delivers it, or the 14-day sweeper suppresses it.
- The Heph host is shared by the workspaces that one worker serves. It runs no code from a workspace, and each turn has its own credential.
- The live stream is a PostgreSQL `LISTEN` per server replica. The stream sends a fresh snapshot after a reconnect.

## Revisit trigger

- All four Pi Durable triggers above are true.
- An evaluation shows that Heph loses context that a compaction instruction or a separate memory would keep.
- Team channels need Heph. That needs its own ADR: a channel is a conversation that the workspace owns, with a restricted tool set.
- A Heph tool gains a side effect. Then the retry after a lost lease needs replay protection.

## Sources

- Pi Durable: [README](https://github.com/earendil-works/pi/blob/main/packages/durable/README.md) and [release post](https://earendil.com/posts/pi-durable/).
- Pi philosophy: [Pi 1.0](https://earendil.com/posts/pi-1-0/), [Agents are hard](https://lucumr.pocoo.org/2025/11/21/agents-are-hard/), [Pi coding agent](https://mariozechner.at/posts/2025-11-30-pi-coding-agent/).
- Anthropic: [Managed Agents](https://www.anthropic.com/engineering/managed-agents), [Harness design for long-running apps](https://www.anthropic.com/engineering/harness-design-long-running-apps).
- Anthropic: [Effective context engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents), [Context editing](https://platform.claude.com/docs/en/build-with-claude/context-editing), [Prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching).
- Chat search as memory: [How Claude's memory works](https://support.claude.com/en/articles/11817273-how-does-claude-s-memory-work).
- OpenAI: [Prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching), [Session memory](https://developers.openai.com/cookbook/examples/agents_sdk/session_memory).
- Context limits: [Chroma, context rot](https://www.trychroma.com/research/context-rot), [How contexts fail](https://www.dbreunig.com/2025/06/22/how-contexts-fail-and-how-to-fix-them.html), [Manus, context engineering](https://manus.im/blog/Context-Engineering-for-AI-Agents-Lessons-from-Building-Manus).
- Slack: [Agents and AI apps](https://docs.slack.dev/ai/), [Streaming messages](https://docs.slack.dev/reference/methods/chat.startStream/), [message events](https://docs.slack.dev/reference/events/message/).
