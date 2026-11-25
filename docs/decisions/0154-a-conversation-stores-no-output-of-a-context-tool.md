# ADR 0154: A conversation stores no output of a context tool

**Status:** Proposed
**Date:** 2026-11-25
**Authors:** Felix T.J. Dietrich

**Builds on:** [ADR 0128](0128-a-derived-record-keeps-its-basis-and-revocation-travels-by-generation.md) (refer, never copy), [ADR 0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md) (the ledger is Hephaestus's memory), [ADR 0096](0096-1-0-is-one-stopped-cutover-from-1-0-0-rc-1-and-keeps-nothing-for-0-x.md) (1.0 is a stopped cutover)

## Context

A conversation turn reads its sources with context tools: `read`, `bash`, `load_context`, and the retired `fetch_context`.
At `turn/end` the server stores each tool result as the agent sends it, output included (`conversation_entry.payload`).
A later turn replays the stored entries from the newest summary on.

The model sees that output for one more turn only.
When a later turn completes, the agent appends a note for each earlier result (`staleContextResults` in `turn.ts`).
A failed or stopped turn appends no notes, so its raw outputs stay in the replay.
An end that the server refuses as too large is sent again with notes (`withContextNotes`), which shows that a turn can do without its stored outputs.

When a source is erased, the copies of its words must go too.
Today a Slack disconnect and a person erasure redact the stored outputs, and the Slack copy retention redacts aged ones.
Other source erasures leave them.
A fence that erases them in the erasing transaction must order its locks against every turn write, eraser and listener.
Each design for it on #5118 opened a new interleaving. [#5158](https://github.com/hephaestus-build/Hephaestus/issues/5158) records them.

## Decision drivers

- An erasure is correct by construction, not by a lock order that each new eraser must keep.
- The words of a source have one home: the source (data minimisation).
- An answer reads current data, not a stale output.
- The replay path stays byte-stable for the provider's prompt cache.

## Considered options

1. **A lock fence around the stored outputs.** Each design opened a new interleaving. Each new eraser, turn path or listener can add one.
2. **Re-render a reference from the source at replay.** A `bash` output has no single source. A re-render also changes stored bytes between turns, which breaks the prompt cache.
3. **Cite each source with `basis_edge` and hide stale outputs on read** (ADR 0128). Pi's `read` and `bash` tools do not report which items they read, so a citation cannot be complete. The words also stay in the database until a sweep runs.
4. **The agent sends a note, and the server checks it byte for byte.** Two derivations, in TypeScript and Java, must agree on trimming, cutting, surrogates and the time format. A mismatch fails every attempt that hits it.
5. **The server writes the note.** Chosen.

## Decision

1. A stored context-tool result holds no output.
   Its message is exactly `{role, toolCallId, toolName, content, isError, timestamp}`, with no `details`.
   Its `content` is one text block, which the server writes.
2. At `turn/end` the agent sends each context-tool result with empty `content` and no `details`.
   The server refuses an end that holds any other content or `details` for such a result, with a clear error.
   It never truncates or rewrites output into a note.
3. The server derives the note from the one call of the attempt with the result's `toolCallId`.
   An end with no such call, or with two, is refused.
   The note forms:
   - ``[read <path>, read at <time>. This result is from an earlier turn and may be out of date; read it again for current data.]``
   - ``[bash `<command>`, run at <time>. This result is from an earlier turn and may be out of date; run it again for current data.]``
   - ``[load_context <path>, loaded at <time>. Each turn has a new context folder: load it again before you read the repository.]``
   - ``[fetch_context, run at <time>. This result is from an earlier turn; read the context folder for current data.]``, for stored rows only.

   `<path>` and `<command>` are the call's string arguments, cut at 200 Unicode code points with `…`.
   A missing argument gives `an earlier path` or an empty command.
   `<time>` is the result's timestamp, formatted as `Ddd YYYY-MM-DD HH:MM UTC` with an English weekday.
   Only the server derives notes, so the agent's `staleContextResults` and `withContextNotes` go.
4. A summary that the attempt appends after its question is an overflow summary, and can quote an output.
   The server does not store it.
   The next stored entry takes the dropped summary's `parentId`, so the replay path stays whole.
   A summary that the attempt appends before its question reads only stored entries, and is stored.
5. One migration converts the stored rows, in batches by id:
   - It rewrites each unredacted context-tool result, `fetch_context` included, to its note, and removes its `details`. It matches the call by `toolCallId` within the same turn. A redacted row stays as it is.
   - It redacts each unredacted summary whose range covers such a result, payload and `text` both.
     The next turn then compacts again from the notes.
   - It runs in the stopped cutover of ADR 0096, so no old writer stores output after it.
   - A restored database runs it again before the server serves, because the migrator applies every changeset that the database lacks.
   - It cannot be undone: the old outputs are the copies that this decision removes.
6. This ADR covers raw tool output only. Other stored words keep their rules:
   - Hephaestus's own words keep the conversation retention. They are an answer, its thinking blocks, a tool call's arguments, and the command that a note repeats.
     They can repeat a source (cascade table, `DERIVED_ANSWER`, #4979).
   - An entry written in a Slack channel belongs to the channel. A Slack disconnect or a channel erasure redacts it with the summaries that cover it (ADR 0128 decision 7).
   - A `search_conversation` result quotes the developer's own entries and goes with them.

## Consequences

- Good: a source erasure has no stored tool output to erase. The context change revokes the sandboxes, which hold the only live output.
- Good: after the migration, the Slack copy retention and the person erasure find no tool output to redact.
- Good: an answer that needs a file reads its current version.
- Bad: a turn reads a file again when the previous turn read it. This costs one tool call and its tokens.
  Every later turn already pays this cost today.
- Bad: no running check measures answer quality. The release evaluation cases of [ADR 0142](0142-a-maintainer-signs-a-manual-release-check-and-an-offline-simulation-checks-contact.md) include short multi-turn cases, but `eval:golden` does not exist yet. We accept the risk until they run. Then one case asks a follow-up question about a file that the previous turn read.
- Bad: the migration removes stored outputs for good.

## Confirmation

Not enforced yet: the #5158 pull request adds these checks, and its review confirms each one.

- A server test: `turn/end` refuses a context-tool result with output, one with `details`, and one with a note that carries extra text.
- A server test: `turn/end` drops an overflow summary and keeps a pre-prompt summary. The next turn's context holds the question, and a turn forced over its threshold compacts again.
- A database test: on seeded rows, each context-tool output, a truncated `read` with `details` and a `fetch_context` result become notes. A covering summary is redacted, a redacted row stays, and no other row changes.
- `CascadeTableTest`: the `SOURCE_ERASURE × CONVERSATION_COPY` cell cites these tests for the scope that they prove.

## Revisit trigger

A release evaluation case shows answers that degrade because a turn lacks the previous turn's outputs.

## Sources

- [GDPR Art. 5(1)(c)](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32016R0679): data minimisation. An output that nothing reads is not necessary for the purpose.
