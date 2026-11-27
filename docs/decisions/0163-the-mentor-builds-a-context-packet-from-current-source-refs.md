# ADR 0163: The mentor builds a context packet from current source refs

**Status:** Proposed
**Date:** 2026-11-27
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0154](0154-a-conversation-stores-no-output-of-a-context-tool.md) (summary coverage and replay), [ADR 0157](0157-the-mentor-core-keeps-what-the-person-saves-and-each-answer-shows-what-it-used.md) decision 8 (What Hephaestus used)

**Builds on:** [ADR 0128](0128-a-derived-record-keeps-its-basis-and-revocation-travels-by-generation.md) (source basis and revocation), [ADR 0158](0158-the-mentor-orders-commitments-per-person-and-answers-per-request.md) (attempts and audiences)

## Context

The mentor works across app threads, Slack DMs and Slack threads.
Each place has its own transcript and readers.
A turn may need an older reply target, a decision from an explicitly continued exchange or fresh practice evidence.
A sequence range can find where to resume replay.
It cannot prove which revisions a summary used or whether its audience can still read them.

## Decision drivers

- Keep each packet within the admitted model's input window.
- Keep the message and readable anchors that the developer expects the mentor to use.
- Prove the current revision and access of every source sent to the model.
- Keep provenance available for **What Hephaestus used**.

## Considered options

1. **Replay the stored path from the latest summary.** This reuses bounded history. A path and a sequence range cannot prove the summary's exact sources or separate mixed history by audience.
2. **Build a packet of scoped reads with exact refs.** This uses more source checks. It preserves the content the attempt needs and makes changes to its sources observable. Chosen.

## Decision

### Context packet

Build one `ContextPacket` for the claimed attempt and place.
Use the `ContextAttempt` projection from the stored verified claim.
The projection carries the request, attempt ID, place, scope and any admitted continuation ref.
The server decides the scope.
Render the sections in this order.

| Section | Initial cap (tokens) | Content and source rule |
| --- | ---: | --- |
| INPUT | 4,096 | The current message with its native ID, revision, author, reply target, native root and surface. |
| PLACE | 6,144 | Recent entries in the exact place and directly referenced readable parents. A separate app continuation loads its exact Slack exchange by ref. |
| COMMITMENTS | 2,048 | Confirmed goals, agreements, decisions and eligible work with its status and results. The app uses owner-authorized items. Slack DMs use the default closed list and admit extra items only under a verified personal grant for the binding. Shared routes receive no personal items. |
| EVIDENCE | 3,072 | Current practice results and evidence refs loaded when needed. Slack uses eligible posted copies for the route. The app uses authorized lookups. |
| CAUSE | 1,024 | The authorized task result, occurrence or review seal that started the turn. |

Use the smaller of 16,384 tokens and the input window left after reserving tokens for system content, tools and the answer.
Count the serialized envelope and provenance in the section limits.
Give each item its role, kind, ID, revision and owner or scope.
Serialize source text as escaped string values.
The server assigns roles and sections, selects tools and sets the recipient.

Check permission before selecting pins.
Pin the input, readable reply target, latest eligible correction and named eligible commitments.
Count each source revision once.
Drop optional items in priority order to fit the caps.
If the pins alone exceed a cap, give a fixed next action to narrow the request and skip the provider call.
An unavailable reply target gives a fixed unavailable result.
An unavailable commitment gives no text or presence hint.

For a personal item in a Slack DM, check the grant, source custody, model and payer, and destination before adding it.
Record its grant generation with its basis edge and carry it into any derived history and summary.
Build the default DM packet independently.
A fallback uses an answer prepared only from that packet.

### Compaction and recall

A place summary is a derived entry with basis edges for the exact message IDs and revisions it covers.
When a compaction uses an earlier summary, preserve that summary's exact coverage refs.
The summary gives no access to its sources.
An edit, deletion, access change or epoch change on a covered entry makes the summary stale.
The next build rebuilds it from the eligible current entries.
Check the coverage again before storing or admitting the rebuilt summary.
Slack excludes legacy mixed summaries and app summaries.
Stored context-tool results follow ADR 0154's rule on tool output.

Provide `recall(query)` as a scoped tool.
In Slack it searches the exact admitted place, including its native thread root when one exists.
In the app it searches the owner's authorized app places and the exchange of any explicit Slack continuation.
Before a hit enters the packet or tool response, recheck its current revision and access epoch under the attempt's scope.
Exclude a stale hit.
Revoke grant-dependent messages and summaries from recall and replay when their grant generation changes.

### Continuation and admitted refs

An explicit app continuation carries a `ContinuationRef` with the Slack place ID, message ID and revision.
The app loads and cites that exchange with fresh access checks.
It stores the ref to the exchange.
Opening or continuing it in the app creates no Slack effect.

Before provider use, recheck and bind every included ref to the attempt.
Include the input, entries in PLACE, any summary and its coverage, commitments, evidence and the cause.
For a sealed review, bind a public handoff packet for the work-place task and a separate private packet for guidance.
**What Hephaestus used** reads the admitted refs for material facts and resolves links under current access.
A stale or unavailable ref gives a fixed unavailable state.
An answer with no recorded basis uses the legacy evidence rules in ADR 0157.

## Consequences

- A stale summary can be rebuilt without changing the transcript.
- The packet can cite an older authorized reply target after compaction.
- Exact coverage and revision checks cost storage and reads.
- A stale source can require a rebuild or a new recall before the turn uses it.

## Confirmation

The C2 implementation tests prove that an edit, deletion or access revocation makes a covered summary stale.
The next build must rebuild it with current coverage.
Recall tests reject stale revisions and epochs and exclude matches in another Slack place.

The 1.0 gate also checks these six cases:

1. A Slack top-level DM and its thread roots stay isolated in PLACE and recall.
2. After compaction, an app reply loads and pins its older exact target by ref.
3. An explicit app continuation cites the actual Slack ask or decision by ref. It creates neither a transcript copy nor a Slack effect.
4. Edited, deleted and revoked anchors stay out of recall and summary results.
5. One sealed review sends scoped evidence and prior guidance to the private packet. Its work-place packet receives only the public handoff.
6. Material facts show links to their sources or appear in **What Hephaestus used**.

## Revisit trigger

Revisit the caps if eligible pins often exceed them or if summary rebuilds delay the first useful answer.
