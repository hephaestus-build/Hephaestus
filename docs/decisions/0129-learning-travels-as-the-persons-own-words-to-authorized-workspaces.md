# ADR 0129: Learning travels as the person's own words to authorized workspaces

**Status:** Proposed
**Date:** 2026-11-23
**Authors:** Felix T.J. Dietrich

**Builds on:** [ADR 0126](0126-a-person-has-one-relationship-and-a-personal-space.md) (the personal space), [ADR 0128](0128-a-derived-record-keeps-its-basis-and-revocation-travels-by-generation.md) (provenance and revocation), [ADR 0130](0130-a-practice-has-a-portable-identity-and-growth-counts-independent-pieces-of-work.md) (portable practice identity)

**Relates to:** [ADR 0134](0134-the-authorization-recheck-runs-at-three-boundaries-with-two-authorization-generations.md) (an authority narrowing bumps a generation)

## Context

A person learns a lesson in one team and needs it in the next.
The work that taught the lesson belongs to the first team.
Its code, its pull requests and its review comments must never leave that workspace.
Some teams, such as an agency that does client work, cannot let even a summary leave.

Most employers run their own instance.
In-instance transfer serves shared instances: a university with many courses, a hosted instance or a large company.
Across instances, the person needs a download that they own.

A model can write a summary that holds a client's facts.
A classifier that searches the summary for such facts can miss one.
The real constraint is that only text with a proven author and closed fields may cross a workspace boundary.

## Decision drivers

- Evidence and work content never leave their workspace.
- Only the person's own words, or text that the person confirmed word for word, may travel.
- The source team, the destination team and the person each have a say.
- Every move is the person's act, visible and reversible.

## Considered options

1. **Learning stays inside each workspace.** A career outlives an employer. A person loses every lesson at each move.
2. **A model summary travels, behind a leak classifier.** A classifier is not proof. One miss moves a client's facts to another employer.
3. **A grant for each record and each destination.** The person already chose to move the record. A second grant for each record repeats that act and adds a screen to every move.
4. **Federation between instances.** Instances trust each other's identities and sync records. Nothing imports yet, and federation is a large protocol for a rare move.
5. **Two hops: a copy into the personal space by the person's act, then use in authorized workspaces (chosen).**

## Decision

Option 5.

1. **Two hops.** A transfer copies a record once from a team workspace into the personal space. An authorized workspace lets that copy shape answers there. Evidence never moves.
2. **What may be copied.**
   - Text that the person wrote.
   - Model text that the person confirmed word for word on a screen. The hash of the shown text proves it.
   - Closed fields: practice identity, outcome, period and counts.
3. **What never travels.** Model prose, noted records and evidence never travel. "Keep and take along" is the person's act that turns a noted record into a person-authored record.
4. **The copy keeps its source in its own columns.** No transfer table exists.
5. **The source decides.** The export policy of each team workspace is Open (the default), Personal only or Closed.
   - Closed blocks even the person's confirmed words.
   - Personal only lets a record reach the personal space, never another team workspace.
6. **The destination decides what it receives:** all learning, or preferences only.
7. **The person decides for each workspace.**
   - Each team workspace has one toggle, "Use what travels with me here". It is on by default when the destination allows learning.
   - Each record has "Keep out of this space".
8. **One card, one visible toggle.** A save card in a team workspace shows the exact words, the destinations and "travels with you". The toggle is on by default under Open, and off and locked under Closed.
9. **Learning arrives without its origin.** A run in the destination does not learn which team the record came from.
10. **Checks at transfer.**
    - The authorship check passes only confirmed text that matches the shown hash.
    - The identifier check refuses a record that names a person who is not a member of the destination.
11. **Every move has a reverse.**
    - Undo removes the copy.
    - A recall by the source workspace moves its copies to `NEEDS_REVIEW`, with reason `RECALLED`.
12. **Providers.** The person sees the AI provider when they set up the personal space. A provider change notifies the person and pauses transfer until they confirm.
    - Local turns continue.
    - Imported context that the new provider may not read is withheld from it.
13. **Across instances, the person downloads their learning.** A portable download carries the same records and fields. An import goes only to the personal space, with no permissions and no evidence.

### What this changes in other ADRs

- No accepted ADR changes. [ADR 0084](0084-every-statement-on-workspace-data-binds-its-workspace.md) stays, because each copy is a new row of one workspace.

## Consequences

- Good: a lesson follows the person from team to team and from employer to employer.
- Good: no model text and no evidence crosses a workspace boundary. The proof is a hash, not a classifier.
- Good: a team with client work sets Closed once, and nothing leaves.
- Bad: a lesson that the model wrote and the person did not confirm does not travel. The person must confirm it word for word first.
- Bad: a noted record never travels by itself. The person must select "Keep and take along".
- Bad: a recall can move a record that the person relies on to review, without the person's act.

## Confirmation

Not enforced yet. The implementation adds these tests:

- An integration test fails when a record of a Closed workspace reaches another workspace, or the toggle is not locked.
- A route test fails when a record whose text changed after it was shown transfers.
- A fixture test fails when a record that names a non-member transfers.
- A fixture test fails when a noted record transfers without "Keep and take along".
- Integration tests fail when Undo leaves the copy, or a recall leaves a copy outside `NEEDS_REVIEW`.

## Revisit trigger

- Several instances ask to exchange learning directly, and a person-held download is not enough.
- People often decline to confirm model text word for word, so useful lessons stay behind.

## Sources

- OpenAI, [Memory FAQ, Business version](https://help.openai.com/en/articles/9295112-memory-faq-business-version). Memories stay apart unless the person merges the workspaces. After a merge, only "supported saved memories" are "eligible to transfer". This confirms the person's act as the only path, with a narrow set of what moves.
- Regulation (EU) 2016/679, the [GDPR](https://eur-lex.europa.eu/eli/reg/2016/679/oj), Article 20. A person has the right to receive their data "in a structured, commonly used and machine-readable format". The portable download of decision 13 is that format for learning.
