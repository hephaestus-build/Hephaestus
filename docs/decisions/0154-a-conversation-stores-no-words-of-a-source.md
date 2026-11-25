# ADR 0154: A conversation stores no words of a source

**Status:** Proposed
**Date:** 2026-11-25
**Authors:** Felix T.J. Dietrich

**Builds on:** [ADR 0128](0128-a-derived-record-keeps-its-basis-and-revocation-travels-by-generation.md) (refer, never copy), [ADR 0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md) (the ledger is Hephaestus's memory)

## Context

A conversation turn reads sources with the `read`, `bash` and `load_context` tools.
Its sources are Slack messages, Outline documents, repository files and the other items of the context folder.
At `turn/end` the server stores each tool result with its full output in `conversation_entry.payload`.
It replays every stored entry into the next turn's Pi session.

When a source is erased, its copies in conversations must go too.
On main, only a Slack disconnect erases them.

Seven review rounds on #5118 tried a lock fence: hold the workspace, revoke the sandboxes, and redact the copies before the commit.
Each round found a new interleaving.
Issue #5158 lists them.
They include an upgrade deadlock, a sandbox started during the erasure, and lock-order cycles with consent pauses and person erasure.
They also include a model request in flight, a nested tombstone that commits alone, and listener order.

The constraint is not the fence. It is that a copy exists.
The stored output has almost no reader.
When a turn completes, the agent already shows the model a note instead of each earlier output (`staleContextResults`, `contextNote` in `turn.ts`).
So after the next turn, nothing reads the stored words.

## Decision drivers

- An erasure is correct by construction, not by a lock order that each new eraser must keep.
- One home for the words of a source: the source (data minimisation).
- Current data in an answer beats a stale verbatim output.
- The replay path stays byte-stable for the provider's prompt cache.

## Considered options

1. **A lock fence around the copies.** Seven rounds each found a new interleaving, and each new eraser, turn path or listener can add one.
2. **Re-render a reference from the source at replay.** A `bash` output has no single source. A re-render also changes stored bytes between turns, which breaks the prompt cache.
3. **Cite each source with `basis_edge` and hide stale copies on read** (ADR 0128). Pi's `read` and `bash` tools do not report which items they read, so the citation cannot be complete. The words also stay in the database until a sweep removes them.
4. **Store a note, never the output.** The next turn reads a file again when it needs it. Chosen.

## Decision

1. A stored `read`, `bash` or `load_context` tool result holds its note, never its output.
   A note names the tool, the path or the command, and the time. It holds nothing that the tool read.
2. The server accepts such a result at `turn/end` only if its content is exactly the note that the server derives itself.
   It derives the note from the tool name, the arguments of the matching tool call, and the result's timestamp.
   Anything else is refused with a clear error, and nothing of the attempt is stored.
   The server never truncates or rewrites a result into a note.
   The note has one of three forms:
   - ``[read <path>, read at <time>. This result is from an earlier turn and may be out of date; read it again for current data.]``
   - ``[bash `<command>`, run at <time>. This result is from an earlier turn and may be out of date; run it again for current data.]``
   - ``[load_context <path>, loaded at <time>. Each turn has a new context folder: load it again before you read the repository.]``

   `<path>` is the call's path argument, or `an earlier path` when it has none.
   `<command>` is the call's command argument, cut at 200 characters with `…`.
   `<time>` is the result's timestamp in UTC.
   The agent's `contextNote` and the server's derivation are one format, with one test vector set that both run.
3. The agent sends its results as notes at `turn/end`, through the path that `withContextNotes` takes today.
   The output lives only in the sandbox's memory, for the turn that read it.
4. `turn/end` stores no summary that overflow compaction made during the turn: that summary can quote an output.
   The next turn compacts again from the stored entries.
5. One migration replaces the output of every stored copying tool result with its note.
   The migration is batched, and cannot be undone: the old outputs are the copies that this decision removes.
6. An answer, a partial answer and a tool call's arguments stay Hephaestus's own words, under the conversation retention.
   A question or answer written in a Slack channel belongs to the channel. It is a source, not a copy.

## Consequences

- Good: a source erasure has no stored copy to erase. The context change that main sends revokes the sandboxes, which hold the only live copy.
- Good: the Slack copy sweep, the source-erasure fence of #5158, and most of the person-erasure copy step are not needed.
- Good: an answer that needs a file reads its current version.
- Bad: a turn that needs a file read in the previous turn reads it again. This costs one tool call and its tokens. Every turn after that already pays this cost today.
- Bad: no running check measures answer quality. The release evaluation cases of [ADR 0142](0142-a-maintainer-signs-a-manual-release-check-and-an-offline-simulation-checks-contact.md) include short multi-turn cases, but `eval:golden` does not exist yet. We accept the risk until they run. Then one case asks a follow-up question about a file that the previous turn read.
- Bad: the migration removes stored outputs for good. A restore from a backup brings them back, and the migration runs again on the next start.

## Confirmation

- A server test: `turn/end` refuses a copying tool result whose text is an output.
  It also refuses a result that has the note's shape but carries extra source text.
- A server test: `turn/end` stores no overflow summary, and the next turn compacts from the stored entries.
- A database test: on seeded rows, every copying output becomes its note, and no other row or column changes.
- `CascadeTableTest`: the `SOURCE_ERASURE × CONVERSATION_COPY` cell changes from a gap to a proved action.

The coordinator accepted this decision on 2026-11-25. Its status becomes Accepted in the pull request that adds the checks above (#5158), because an accepted ADR names checks that exist.

## Revisit trigger

A release evaluation case shows answers that degrade because a turn lacks the previous turn's outputs.

## Sources

- [GDPR Art. 5(1)(c)](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32016R0679): data minimisation. A copy that nothing reads is not necessary for the purpose.
