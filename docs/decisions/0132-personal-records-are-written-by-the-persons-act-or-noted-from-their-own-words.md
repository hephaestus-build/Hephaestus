# ADR 0132: Personal records are written by the person's act or noted from their own words

**Status:** Proposed
**Date:** 2026-11-23
**Authors:** Felix T.J. Dietrich

**Builds on:** ADR 0131 (proposed in #4862, the conversation and its ledger)

**Relates to:** [ADR 0133](0133-a-check-in-is-an-agreed-contact-with-a-scope.md) (a check-in is saved like a record)

## Context

On main, the conversation ledger is the only memory ([ADR 0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md) decision 9).
Hephaestus writes no notes, and a summary is the only thing that outlives the 400-day retention.
A person who says "short answers, command first" must say it again in every workspace, and after each compaction.
A goal has no row, so nothing can follow up on it.

Memory that shapes answers is also a risk.
A model can misread the person, and a wrong note travels into every later turn.
Text that someone else wrote, such as a pull request description, can try to plant a note.
The real constraint is that the person can see and fix everything that shapes an answer, and that only the person's own words become memory.

## Decision drivers

- The person tells Hephaestus a fact once.
- The person sees everything that Hephaestus keeps in mind, and can forget any item in one step.
- Only the person's own words become memory. Other people's text never does.
- A note is never evidence about the person's work.
- A card asks for a decision only where the decision is real.
- Each act on a record has a reverse.

## Considered options

1. **No memory beyond the ledger, as on main.** The person repeats facts, and nothing follows up on a goal. A summary keeps context only until the next erasure.
2. **A card to confirm each fact.** Every remembered fact waits on a card. The person must answer many small questions, and most people stop. ChatGPT, Claude and Copilot write memory without a card and show it in a list.
3. **Rules that the person writes by hand,** as Cursor rules are. Nothing is remembered unless the person edits a file. It fits configuration, not a conversation.
4. **Memory from the whole conversation, written by the model.** Text that others wrote can plant a note. A misread spreads with no visible source.
5. **Person-authored records, notes from the person's own words only, and one visible list with Forget (chosen).**

## Decision

Option 5.

1. **Help settings** belong to the account and apply in every workspace. They are a closed set: answer length, command first, language, form of address and help style.
   - Help style is "Give me a hint first" or "Just tell me".
   - A turn sets them from the person's own words, with no card. The person can also set them in Settings.
   - They carry no employer content.
2. **A person-authored record** is an `owned_record` in its home workspace.
   - Kinds are `PREFERENCE`, `CONTEXT`, `LESSON`, `REFLECTION`, `CORRECTION` and `GOAL`. Goals and memories are filtered views of it.
   - States are `ACTIVE`, `CLOSED` and `NEEDS_REVIEW`. Every state change that the person causes has a reverse.
   - Each revision records its author kind (`PERSON` or `HEPHAESTUS`) and the hash of the exact text shown.
3. **A noted record** has author kind `HEPHAESTUS` and kind `PREFERENCE` or `CONTEXT`.
   - A turn writes it from the person's own messages in that workspace, with no card.
   - Code checks that each cited entry has the person as its origin. A prompt rule alone is not enough.
   - It is never evidence about the person's work, and never about a third party.
   - It never supports a card or another record, and it never gets a grant.
   - It never travels. The person's **Keep** turns it into a person-authored record with author kind `PERSON`. Only then can **Take along** transfer it.
4. **A kept-in-mind item** comes from compaction and stays in its workspace for 400 days. The person can keep it.

5. **One list shows every kind.** It holds the help settings and, for each workspace, the noted records and the kept-in-mind items, with their sources.
6. **Each line has up to three actions.**
   - **Forget** redacts the item and leaves a tombstone. Hephaestus does not note it again until the person says it again.
   - A Forget or a Not right also fences every running turn that read the item. ADR 0134 (proposed in #4863) owns that fence.
   - **Not right** records a correction request. The item stops shaping answers until new evidence supports it.
   - **Keep** turns a kept-in-mind item into a person-authored record, under record retention.
7. **Freshness.** A noted `CONTEXT` record expires 120 days after its last use or confirmation. Help settings and noted preferences do not expire.
8. **A new note on the same subject supersedes the old one.** The old one stays in the record's history. Notes never stack.
9. **The always-loaded block has a hard size limit.** It fills in this order: goals, corrections, help settings, preferences, context. Recall searches the rest.
10. **Retention belongs to the person.** Records stay until the person deletes them, erases the account, or leaves a workspace under a Closed policy. An operator may set a maximum of at least three years.

11. **An act on the person's own workspace saves in the conversation, with Undo.** A goal, a help setting or a check-in is saved at once. The entry says "Saved as a goal · Undo · Edit". Undo is the reverse, on the same acceptance path.
    - A local save never transfers. A transfer is the separate, confirmed **Take along** card of ADR 0129 (proposed in #4863) decision 8.
12. **A card appears only where a decision is real.** Examples are a transfer, a Slack place, a self-report and anything public.
13. **A card's payload never changes.** The server renders its consequence as codes with parameters, and a digest of them. The consequence says where the record lives, what it reaches, who reads it and when it comes.
14. **Acceptance runs in one transaction.** It checks the actor, the edits, the current consequence digest and the provenance reference. It also checks the policies and the limits, then applies the effect.
15. **The first commit wins.** A card whose consequence changed is refused with `changed-since-shown`. A declined card stays declined until its source changes.
16. **A model never writes a goal, a check-in or a person-authored record without the person's act.**

### What this changes in other ADRs

- ADR 0131 (proposed in #4862) supersedes ADR 0082. Its table lists the two decisions of ADR 0082 that this ADR changes: 9 (memory) and 17 (retention).
- ADR 0129 (proposed in #4863) owns how a person-authored record travels. This ADR only says that a noted record never does.

## Consequences

- Good: the person says a preference once, and it holds in every workspace.
- Good: everything that shapes an answer is in one list, and Forget works in one step.
- Good: text that someone else wrote cannot become a note, because code checks the origin of each cited entry.
- Bad: a noted record can still misread the person. It stays in its workspace, it is never evidence, and the person can forget it.
- Bad: an item that the person never keeps is gone after 400 days, also when it was useful.
- Bad: a fact that the person states in Slack becomes a note in the team workspace of that Slack team. The export notice of ADR 0123 (proposed in #4862) says so.
- Bad: the hard limit of the always-loaded block means that some records reach an answer only through recall.

## Confirmation

Not enforced yet. The implementation adds these tests:

- `NotedRecordOriginTest` fails when a noted record cites an entry whose origin is not the person.
- `NotedRecordNeverTravelsTest` fails when a noted record passes the transfer path without **Keep** and **Take along**.
- `ForgetLatchTest` fails when Hephaestus notes a forgotten item again before the person says it again.
- `NoteSupersedesTest` fails when two active notes on one subject exist.
- `ChangedSinceShownTest` fails when a card whose consequence digest changed is accepted.
- `SaveUndoTest` fails when Undo of a save in the conversation leaves the record or its effect.

## Revisit trigger

- Over four weeks, people use Forget or Not right on more than 10 % of noted records.
- People ask for a noted record to follow them across workspaces without **Keep** and **Take along**.
- An evaluation case shows a memory-induced agreement with a wrong claim.

## Sources

- OpenAI, [Memory FAQ](https://help.openai.com/en/articles/8590148-memory-faq). "You can ask ChatGPT to forget a saved memory or delete it from Memory settings." ChatGPT saves memories without a card. This supports option 5 over a card for each fact.
- GitHub, [Copilot memory](https://docs.github.com/en/copilot/concepts/agents/copilot-memory). People "can review the preferences Copilot has stored" and delete them. This supports one list with Forget. Its memories also expire without reinforcement, as a noted context record does here.
