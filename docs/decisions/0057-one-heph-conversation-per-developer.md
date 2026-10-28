# ADR 0057: One Heph conversation per developer, on a stateless shared Heph host

**Status:** Accepted (amended 2026-10-28 — Heph's host and turn sandbox superseded, [ADR 0068](0068-reviews-and-heph-run-on-one-agent-runtime.md))
**Date:** 2026-10-09
**Authors:** Felix T.J. Dietrich

**Amends:**

- [ADR 0005](0005-two-role-runtime-via-conditional-on-property.md), [ADR 0006](0006-llm-proxy-on-coordinator-trust-model.md), [ADR 0007](0007-sandbox-spi-shape.md), [ADR 0008](0008-webhook-runtime-role.md).
- [ADR 0009](0009-worker-runtime-substrate-wss-control-channel.md), [ADR 0026](0026-per-purpose-agent-bindings-and-llm-governance.md), [ADR 0036](0036-agent-runtime-runs-on-node-24.md), [ADR 0041](0041-compose-1x-kubernetes-2.md).

**Builds on:**

- [ADR 0046](0046-heph-follows-its-model-not-a-workspace-switch.md): the precedent for a drop in the same release.
- [ADR 0045](0045-activity-counts-work-and-never-ranks-people.md): no score, rank, or streak.

## Context

Before this decision, an account had many Heph chats in a workspace, and one more chat for each top-level Slack direct message.
Each chat had its own Pi session.
A per-developer sandbox container on a worker ran the session.
The server kept the session as one blob in `chat_thread.session_jsonl`.
It kept a second copy of the visible messages in `chat_message.parts`.
Heph showed conversation feedback with `show_feedback`, and the feedback's `CHAT_TURN` copy pointed to a `chat_message`.

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
- Feedback in conversation counts as delivered only when the developer can see Heph's words ([feedback language](../contributor/practice-feedback-language.md#destinations)).
  Unknown visibility never permits a second write.
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
3. **Slack agent sessions with the native stop button in threads.** First rejected, then chosen ([Amendment of 2026-10-22](#amendment-of-2026-10-22-slacks-stop-in-a-heph-thread)).
   The first rejection said: a stop event names no turn when no stream exists yet.
   Binding it needed event receipts, session owners and a clock-skew rule.
   Threads use 👀 as the working signal, and Stop is in the app.

Weekly digest:

1. **A model writes a weekly summary.** Rejected. It is a new claim about a person, outside admission, approval, and withdrawal.
2. **Java writes a digest from facts. A pointer with no content tells the developer that it is ready.** Chosen.

## Decision

### One conversation, Heph threads, and where the answer appears

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
- Slack can leave a reply unconfirmed. If the Slack history then does not show it, the reply stays only in the app.
  A reply can also reach Slack only in part. A stream showed part of it, and then Slack or Silent Mode refused the rest.
  In both cases, the app marks it "Not fully delivered to Slack" and shows the full answer. Hephaestus never posts it a second time.
  This is the practice model's rule for an uncertain write.
  It applies to the whole answer, because an answer can show feedback.
- A developer can stop any active turn in the app.
  A `stopped_by_user` response to a running turn's stream append also stops that exact turn.
  The response identifies one stream and its owning turn. It does not stop queued follow-ups.
- In a Slack thread, Slack's agent session shows that Heph works, with Slack's Stop.
  Its stop event (`agent_session_stopped`) stops the thread's running Slack turn only ([Amendment of 2026-10-22](#amendment-of-2026-10-22-slacks-stop-in-a-heph-thread)).

The app offers Helpful and Not helpful on each Heph answer.
Ratings come from these buttons only. Heph's own 👀 reaction is its working signal, and Hephaestus does not read Slack reactions.
New chat, the chat list, regenerate, edit-and-resend, Start fresh, and steer are removed.

The account owns the Heph conversation (`mentor_conversation.account_id`), as it owns every other person-level row.
The key is the account, not the membership, so the removal of a member never waits for the conversation's erasure.
When one account absorbs another, the absorbed account's conversation moves with it.
If both accounts have a main conversation in the workspace, the absorbed entries follow the claiming account's entries, and the absorbed summaries are erased.
The next turn summarizes again.

### The ledger

Three workspace-scoped tables hold Heph's state.
Each has a unique `(id, workspace_id)` key, and each reference inside a workspace names the workspace too:

- `mentor_conversation`: a main conversation or a Heph thread, with the Slack team, user, and channel of the direct message.
- `mentor_entry`: each question, answer, tool result, system entry, summary, and weekly digest. A Pi entry is stored verbatim in `payload`.
- `mentor_turn`: one question's run, with its status, lease, partial answer, and usage.

These concepts come from Pi Durable:

- A client `request_id` makes each submission idempotent.
- A question for a busy conversation waits as a follow-up. A conversation holds a limited number of follow-ups.
- The server commits a row before the app or Slack shows it.
- The partial answer is one whole snapshot, sent at a limited rate.
- The live protocol sends a snapshot first, then events. `NOTIFY` carries ids, and a turn's attempt, status and origin (app or Slack). It never carries content.
- A Slack post goes from intent to effect to outcome: `PENDING`, then `DELIVERED`, `UNCERTAIN`, or `FAILED`.
  The question carries the state of the whole reply, and the server that claims it projects the turn.
  A reconciler settles a reply that no server settled.
  It sends a reply that no server took.
  For a reply that a server took, it reads the Slack history once and never posts again.
  [Heph runtime](../contributor/unified-pi-runtime.mdx#slack-replies) gives the rules.
- A turn that loses its lease runs once more with the same key. A second loss fails it.
  A stopped turn that loses its lease stays `STOPPED`.
  The host sends the entries of a turn once, at its end.
  Thus, a lost attempt stores nothing, and its retry starts clean.
- The usage of a turn is on the turn row.

Stop has two forms.
A running turn becomes `STOPPED` and keeps Pi's cut-off answer.
A queued turn becomes `STOPPED` and keeps the question with no answer.

Heph keeps the three citation tools of the practice model ([ADR 0056](0056-one-practice-model-for-items-and-scans.md)) unchanged: `link_result`, `link_problem` and `show_feedback`.
`link_result` and `link_problem` cite a result or a problem. They deliver nothing.
`show_feedback` shows ready feedback whose destination is `CONVERSATION`.

The claim binds to the turn, and the receipt binds the message:

- While the `show_feedback` call runs, the server claims the feedback's `CHAT_TURN` copy for the turn and its attempt (`feedback_copy.mentor_turn_id`).
  A second call for the same feedback in the same attempt keeps the claim.
- The end of the turn stores Heph's answer. The receipt then names that message (`feedback_copy.mentor_entry_id`).
- The receipt follows the practice model's rule.
  An app answer that ended `DONE` and shows the words is `DELIVERED`.
  A claim whose turn stopped or failed after the call is `UNCONFIRMED`.
  A claim that the answer never showed is `FAILED`.
  A Slack reply that Slack confirmed, or that the Slack history shows, is `DELIVERED`.
  A Slack reply that the history does not show is `FAILED`. A reply whose history cannot be read is `UNCONFIRMED`.
  Silent Mode is `FAILED`.
- A lost attempt's claim is `UNCONFIRMED` before the retry starts, and the retry's brief no longer lists that feedback.
  Thus, Heph shows one piece of feedback at most once.

A lost attempt stores no Heph message, so no placeholder row exists.
[Heph runtime](../contributor/unified-pi-runtime.mdx) is the home of the claim and the receipt.

### The Heph host

Each worker runs one long-lived Heph host container from the agent image.
The worker starts it on the first turn that the worker claims.
The host runs many turns at once, one Pi `AgentSession` for each turn.
It has four tools of its own: `search_conversation`, `link_result`, `link_problem` and `show_feedback`.
Where the worker can serve them, it also has `read`, `bash` and `load_context` ([Context folder and turn sandbox](#context-folder-and-turn-sandbox)).
These three tools run in a turn sandbox on the worker, never in the host.

The host can also read the practice-script library that the agent image ships.

A worker claims a queued turn when PostgreSQL notifies it.
It sends `start` and `stop` commands to the host over one WebSocket that the host opens.
The host reads the turn's context and reports the partial answer over HTTP.
At the end of the turn, it sends all Pi entries that the turn appended in one request.

The host uses a turn-scoped `TurnJwt` for these calls and for model calls through the worker's LLM proxy.
The proxy's authentication provider accepts the credential only for its own turn.
It accepts a model call only while the attempt holds the lease.
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

The path renders each question with its time and its origin, for example `[Thu 2026-10-08 09:12 Europe/Berlin · Slack]`.
It marks a stopped answer and a failed turn.
It leaves out erased content. A failed attempt stores only the summaries that it wrote before its question.
A Heph thread sees the main conversation only up to its parent, and the summary at or before that parent.

The situation message gives the time now, the origin of the question, and the gap since the last message.
It also gives the open Heph threads, the page of the floating panel, and the conversation feedback that waits.
It tells Heph to read `INDEX.md` of the context folder first.

Context management:

- **Tool results.** When a turn settles, Pi `context_edit` entries replace older `read`, `bash` and `load_context` results with a reference.
  Only the latest turn's results stay verbatim. Heph can read again.
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

### Context folder and turn sandbox

The context documents give the files and line counts of a pull request, not its code.
Thus, Heph could not answer a question about a line, a diff, the history, or the blame of a file.
Heph also read each document as a whole through a key.
It could not search across documents or read one part of a large document.

Heph explores a [context folder](../contributor/practice-review-glossary.mdx#runtime-terms) with three tools: `read`, `bash` and `load_context`.
The folder is in a [turn sandbox](../contributor/practice-review-glossary.mdx#runtime-terms) on the worker.
A practice review already works this way, because its job folder is the context folder of the review.
Only the placement is different:

| | Practice review | Heph turn |
| --- | --- | --- |
| Image | The agent image | The same image |
| Container security policy | `ContainerSecurityPolicy` | The same policy |
| Folder | Documents under `inputs/` and checkouts under `repos/`, read-only | `INDEX.md`, documents under `inputs/context/`, and checkouts under `repos/<host>/<path>/`, read-only |
| Sources | The `ContentSource` beans and `RepositoryCheckouts` | The same sources |
| Lifetime | One job attempt | One turn attempt. The worker removes the sandbox when the attempt ends and never uses it again. |
| Network | The worker's gateway and LLM proxy, because Pi runs inside | None. The worker stages the files through the Docker API. |
| Credentials | A job token | None |
| Where Pi runs | In the sandbox | In the shared Heph host. Only the tool calls run in the sandbox. |

The tools keep Pi's contracts:

- `read` returns one page of a file, with Pi's limits and notices.
- `bash` runs one command in the folder and returns the end of its output. The image has `git`, `rg`, `jq` and `node`.
- `load_context` stages one heavy item, for example a repository at a branch, a commit, or a pull request.

Each integration contributes its documents and, if it has heavy items, a [context loader](../contributor/practice-review-glossary.mdx#runtime-terms).
The SCM integration loads a repository at a ref, with the Git history that the ref reaches.
Core routes a path to a loader by its prefix and names no repository.

To keep the start fast, each worker keeps a small pool of empty sandboxes.
The first tool call of an attempt takes one and stages `INDEX.md` and the documents into it.
[Heph runtime](../contributor/unified-pi-runtime.mdx#context-folder) gives the measured times.

We rejected these options:

- **A virtual tree of generated files over Git.** Git commands with fixed arguments still take operands that the model selects. The generated views have no end. Search, list and long lines needed our own tools. A real checkout in a real sandbox has none of these problems.
- **One warm sandbox per conversation, across turns.** Person data then outlives the turn, also after an erasure or a consent change. Heph threads that run at the same time share files that change. The sandbox becomes stale. A pool of empty sandboxes gives the same start time without these problems.
- **Pi inside the sandbox for each turn.** The stateless host and its concurrency model are simpler with Pi outside. Pi Durable recommends the harness and the credentials in the trusted process. Only the tool work goes to the environment.
- **Pi code mode in the shared host.** The model's code then runs in a process that serves many workspaces. `bash` with `node`, `jq` and `git` in the sandbox gives the same composition.
- **The earlier program sandbox for each run.** The tool `run_repository_program` sent a TypeScript program to a new review-class sandbox for each run. Each run took 3 to 7 seconds and held one repository at one ref. The program needed its own runner and kit, and could not read the context documents.

#### Security boundary

The sandbox container is the security boundary, as it is for the shell of a review.
The container has these properties:

- The agent image and `ContainerSecurityPolicy`, the same as a review sandbox.
- No network and no credential. The worker stages each file through the Docker API.
- A read-only folder. The worker writes it as root and then removes the write permission. The model writes only to `/tmp`.
- Commands run as the unprivileged user of the image, with no environment from the host or the worker.
- 1 CPU, 1 GiB of memory, and at most 256 processes.

The Heph host runs no code from a workspace.
It sends each tool call to the worker with the turn's `TurnJwt`, and the worker runs it only while the attempt holds the lease.
A load checks the scope of the turn: its workspace, its account, and mentoring as the purpose.
The rules are the same as for Heph's SCM documents: an active connection, a monitored repository, and no team that hides it.
An unknown, unmonitored, hidden, or other workspace's repository gets one error, `NOT_VISIBLE`, with one text.
Thus, a hidden repository looks the same as a repository that does not exist.

The code and the Git history of a monitored repository are workspace material.
The consent rules of contributors apply to the person data that Hephaestus captures: comments, reviews, Slack messages and Outline documents.
That data stays behind the gates of its `ContentSource`, also when Heph reads it from the folder.

#### Custody

The sandbox and its files exist only during the attempt.
When the attempt ends, the worker removes the sandbox with its files.
An attempt ends with an answer, a stop, a deadline, a lost lease, a lost host, or a retry.
A sandbox never goes back to the pool.

A change to what a workspace's turns may read removes every turn sandbox of that workspace.
These changes are an erasure, a hidden repository, a change of monitored repositories, and a change of a developer's AI choice.
The next tool call of the running turn gets `CONTEXT_CHANGED` once, and the call after it gets a new sandbox.

Each `read`, `bash` and `load_context` result is a copy of person data.
The ledger classifies it as such a copy (`MentorPiEntries`), so the erasure path and the copy fence apply.
When a turn settles, Pi `context_edit` entries replace older results of these tools with a reference ([Context model](#context-model)).

#### Limits

| Limit | Value |
| --- | --- |
| One `read` page | 2,000 lines or 50 KB |
| Output that `bash` returns | The last 2,000 lines or 50 KB. The full output stays in `/tmp` for that attempt. |
| Time of one command | 60 seconds, and never past the deadline of the turn |
| Loads in one attempt | 5 |
| Loads at once on one worker | 2 |
| Empty sandboxes in the pool of one worker | 2 |

Heph cannot build the code, run tests, or write to a repository.
The sandbox has no network and no dependencies of the project.
A load fetches a ref that the mirror does not have.
Otherwise, the repository is as fresh as the last sync of its mirror.
`INDEX.md` gives the time of the documents.

The context folder has no setting.
The host offers the three tools only when the worker can start turn sandboxes.
`repos/` lists repositories only where the worker has Git mirrors.

[Heph runtime](../contributor/unified-pi-runtime.mdx#context-folder) gives the endpoints and the pool.
The [threat model](../admin/threat-model.mdx#boundary-2-llm-agent-sandbox) gives the controls.

We revisit this decision when one of these triggers is true:

- Developers need Heph to build the code or run tests. That needs network access and dependencies, and its own ADR.
- An evaluation shows that Heph reads the same large item again in many turns of one conversation.
- The pool cannot keep the start time of the first tool call below one second.
- An integration other than SCM needs a context loader for a heavy item.

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
It shows no score, rank, or streak (ADR 0045).
A quiet week writes nothing.

The digest is a pointer, not a delivery. It creates no feedback copy.
It decides by destination what reached the developer in the week:

- `ON_WORK`: feedback whose delivery time is in the week and whose state is `DELIVERED`, `UNCONFIRMED` or `REPLACED`.
  On the work, an unconfirmed copy can be visible, and a replaced copy was visible.
- `CONVERSATION`: feedback that became `DELIVERED` in the week, with a link to the Heph message that showed it.
- `PRACTICE_PAGE`: never listed as reached, because there `DELIVERED` means that the developer already opened it.
  Instead, the digest counts this feedback when it became ready in the week and the developer has not read it.

"What changed" uses the Practice profile's own change comparison, so the digest and the profile agree.
The app renders the digest at read time.
Thus, feedback that is no longer visible drops out of it.
A reply in the digest's Heph thread discusses results and problems with `link_result` and `link_problem`.
`show_feedback` is refused there, because that feedback is not ready feedback.

A Slack note says that the digest is ready.
When the developer has no Slack identity, an email says it instead.
When neither reaches the developer, the digest is only in the conversation.
The note has no counts, no practice names, and no feedback text.
A reply in the digest's Heph thread is an ordinary Heph turn.
The digest replaces the count-only Slack reminder.

A note that Slack cannot confirm follows the Slack rule above: one history read, and no second post.

### Exception: drop the chat tables in the same release

Rule 3 of the [changelog immutability policy](../contributor/database-migration.mdx#changelog-immutability-policy) requires two releases to drop a table: stop using it, then drop it.
The rule keeps a rolling upgrade safe, when old and new code run at the same time.

Heph 1.0 is a maintainer-approved exception to rule 3, beside the practice model cutover ([ADR 0056](0056-one-practice-model-for-items-and-scans.md)).
One changelog drops the released `chat_thread`, `chat_message`, `chat_message_vote` and `mentor_slack_thread` of the practice model.
It sets `feedback_copy.chat_message_id` to null, and then renames it to `mentor_entry_id`.
It also drops `workspace_agent_binding.allow_internet`.
Feedback rows and their states stay.
The message link of an earlier conversation copy does not, also for the `CHAT_TURN` copies of the practice model.

This is an exception, for these reasons:

- Hephaestus is before 1.0, and ADR 0046 dropped a column in the same release.
- The old session blobs do not map to the ledger. We accept the loss of earlier Heph conversations.
- Keeping the tables one more release would mean writing a deleted feature twice.

The migration note tells operators to stop every role before the upgrade.
Thus, no old code reads a dropped table or column.
`scripts/release-upgrade-test.ts` upgrades a v0.94.2 database with chat history and `CHAT_TURN` copies.
It proves that a fresh and an upgraded database have the same schema.
This exception does not change the rule for other tables and columns.

### Amendment of 2026-10-22: Slack's Stop in a Heph thread

The first decision rejected Slack's agent session and its stop event (option 3 of Slack egress).
A stop event names no turn before a stream exists.
Binding it was thought to need event receipts, session owners and a clock-skew rule.
The maintainer later chose the stop event.
While a reply streamed in a Slack thread, Slack showed its working indicator without a Stop control, and only the app could stop the turn.

The binding is now as follows:

- `SlackAgentSessionStoppedMessageHandler` takes the event only from the developer's own DM.
  The team's current Slack connection must match.
  The DM of the main conversation, the existing parent entry and its existing Heph thread must also match.
  It opens no thread and takes no turn id from the event.
- The event stops the thread's running Slack turn only, under the turn's lock.
  The event time (`event_ts`) must be after the start of the running attempt.
  When the event names streams, one of them must also be the stream that the question records.
  Queued follow-ups stay queued.
  The stop calls no Slack API.
- There is no event receipt.
  Webhook publication uses `event_id` for the configured JetStream deduplication window.
  A stop of an ended turn changes nothing, and a stop from before the attempt started stops nothing.
- There is no clock margin.
  The comparison relies on Slack's clock and the database's clock.
  A difference between them can make a stop just after a start do nothing.
  In a narrow window, it can also let a delayed stop of an earlier attempt stop the next attempt.
  The recorded stream is more evidence, but it names no attempt.
  After a lost lease, it names the lost attempt's stream until the projector starts the answer again.
- The projector owns the session's status.
  It sets `processing` when a thread turn starts to run.
  It ends a stream or turn with the status of the thread's running Slack work.
  A turn that started meanwhile keeps `processing`.
  Only running Slack work shows `processing`.
  A queued question does not.
- These Slack calls are made one at a time for each thread on every server (`MentorSlackDelivery#callInThreadSession`).
  Each first holds the developer's person-data write fence, as every write about the developer does.
  If the fence refuses the developer, for example after an erasure request, no Slack call is made.
  Then each holds the main conversation's row shared, then the thread's row, until Slack answers.
  An erasure waits at the fence for such a call, and the call waits at the fence for an erasure.
  A claim of a turn takes neither row.
  While such a call waits for Slack, appends to that thread and writes that lock the main conversation's row wait.
- Each HTTP call of the Slack gateway's own client has a deadline of 10 seconds.
  A throttled call waits for its whole `Retry-After`.
  The wait and two more HTTP calls must fit in the remaining budget.
  A call that runs out of time leaves the effect of its write unknown, as before.

## Consequences

- The upgrade deletes all earlier Heph conversations. Slack keeps its own copy of the direct messages.
- Operators reinstall the Slack app for `reactions:write`.
- Operators add the `agent_session_stopped` event subscription to the Slack app. It needs no other OAuth scope.
- The Traefik sticky cookie, the interactive sandbox, its configuration keys, and its metrics are removed.
- `hephaestus.worker.capacity.mentor-max` gets a new meaning ([Runtime roles](../admin/runtime-roles.mdx#tunables)).
- The worker control protocol carries no mentor frames.
- The agent image runtime contract is 8. The image provides Node and the Pi SDK. The worker stages `heph-host.ts`, its modules, and the system prompt from its own JAR (`HephHostSpec`).
- A Heph turn that a worker loses runs again on another worker. A read-only tool call can then run twice.
  `show_feedback` cannot claim the same feedback again, because the lost claim is unconfirmed before the retry.
- A Slack reply that Slack cannot confirm is never posted twice. The developer finds it in the app.
- Every write to the ledger holds the developer's person-data write fence. After an erasure request, Heph stores nothing new about that person.
- The Heph host is shared by the workspaces that one worker serves. It runs no code from a workspace, and each turn has its own credential.
  `read`, `bash` and `load_context` run in a turn sandbox for each attempt, never in the host ([Context folder and turn sandbox](#context-folder-and-turn-sandbox)).
- Each worker keeps 2 empty turn sandboxes, and starts one more for each running turn that reads its context folder.
- The live stream is a PostgreSQL `LISTEN` per server replica. The stream sends a fresh snapshot after a reconnect.

## Revisit trigger

- All four Pi Durable triggers above are true.
- A trigger of [Context folder and turn sandbox](#context-folder-and-turn-sandbox) is true.
- An evaluation shows that Heph loses context that a compaction instruction or a separate memory would keep.
- Team channels need Heph. That needs its own ADR: a channel is a conversation that the workspace owns, with a restricted tool set.
- A Heph tool gains another side effect. Then the retry after a lost lease needs replay protection, as `show_feedback` has it.

## Sources

- Pi Durable: [README](https://github.com/earendil-works/pi/blob/main/packages/durable/README.md) and [release post](https://earendil.com/posts/pi-durable/).
- Pi philosophy: [Pi 1.0](https://earendil.com/posts/pi-1-0/), [Agents are hard](https://lucumr.pocoo.org/2025/11/21/agents-are-hard/), [Pi coding agent](https://mariozechner.at/posts/2025-11-30-pi-coding-agent/).
- Anthropic: [Managed Agents](https://www.anthropic.com/engineering/managed-agents), [Harness design for long-running apps](https://www.anthropic.com/engineering/harness-design-long-running-apps).
- Anthropic: [Effective context engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents), [Context editing](https://platform.claude.com/docs/en/build-with-claude/context-editing), [Prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching).
- Chat search as memory: [How Claude's memory works](https://support.claude.com/en/articles/11817273-how-does-claude-s-memory-work).
- OpenAI: [Prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching), [Session memory](https://developers.openai.com/cookbook/examples/agents_sdk/session_memory).
- Context limits: [Chroma, context rot](https://www.trychroma.com/research/context-rot), [How contexts fail](https://www.dbreunig.com/2025/06/22/how-contexts-fail-and-how-to-fix-them.html), [Manus, context engineering](https://manus.im/blog/Context-Engineering-for-AI-Agents-Lessons-from-Building-Manus).
- Slack: [Agents and AI apps](https://docs.slack.dev/ai/), [Streaming messages](https://docs.slack.dev/reference/methods/chat.startStream/), [message events](https://docs.slack.dev/reference/events/message/).

## Update — 2026-10-28: where Heph's loop and tools run ([ADR 0068](0068-reviews-and-heph-run-on-one-agent-runtime.md))

ADR 0068 supersedes § The Heph host and § Context folder and turn sandbox.
Heph's loop runs as one Deployment, and each turn attempt's tools run in a tools Pod that holds no token and has no network egress.
The Heph host, its WebSocket channel, its gateway session token, the turn sandbox and its stager go.
Each turn attempt carries an attempt token that names only the turn, its workspace and the attempt.
The ledger, the conversation model, the context model and delivery of this decision stay.
