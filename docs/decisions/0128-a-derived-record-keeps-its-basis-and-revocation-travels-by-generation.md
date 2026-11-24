# ADR 0128: A derived record keeps its basis, and revocation travels by generation

**Status:** Proposed
**Date:** 2026-11-23
**Authors:** Felix T.J. Dietrich

**Builds on:** [ADR 0127](0127-every-record-and-route-resolves-to-concrete-readers.md) (readers and the definer functions), [ADR 0094](0094-a-provider-mirror-changes-only-on-a-definitive-answer-and-a-tombstone-is-not-an-erasure.md) (a tombstone is not an erasure)

**Relates to:** [ADR 0105](0105-generated-text-carries-a-signed-provenance-record.md) (provenance on generated text), [ADR 0134](0134-the-authorization-recheck-runs-at-three-boundaries-with-two-authorization-generations.md) (authorization generations)

## Context

Hephaestus now derives records from other records.
A noted preference cites the messages that it came from.
A summary covers entries, a card cites a review, and a lesson can come from a suggestion.

When a person deletes a message, every record that came from it must stop shaping answers.
When a person forgets a preference, Hephaestus must not note it again from the same words.
When a workspace removes a source, the people whose records cite it must not lose their own words.
A database restore must not bring back what a person erased.

The real constraint is that a revocation must be complete in the transaction of its cause, without a cascade across people at that moment.

## Decision drivers

- A deletion or a revocation takes effect at once, for every record that depends on it.
- Someone else's erasure never deletes the person's own words.
- A forgotten item stays forgotten.
- One table says what each removal does, and a test checks that it covers every dependent kind.

## Considered options

1. **Copy the source text into each derived record.** An erasure must then find and change every copy, in tables of many people. A missed copy keeps erased text alive.
2. **An eager cascade at the cause.** The deletion changes every dependent record in its own transaction. That transaction crosses people and grows with the data. A partial failure leaves an inconsistent state.
3. **Delete the derived records and the person's own records alike.** It is simple. A team's erasure of a source then deletes a lesson that the person wrote in their own words.
4. **Provenance references, generations, lazy validity and durable cleanup, with one cascade table (chosen).**

## Decision

Option 4.

1. **Refer, never copy, inside a workspace.** A derived record cites its sources through one table of provenance references, `basis_edge`. Its source kinds are an entry, a record and a suggestion.
2. **A revocation bumps a generation.** Every consumer of the revoked source becomes stale in that transaction.
   - Validity is checked when a record is read. A stale record never shapes an answer.
   - Staleness is transitive. A record derived from a stale record is stale too.
   - A turn that already read the source is fenced by [ADR 0134](0134-the-authorization-recheck-runs-at-three-boundaries-with-two-authorization-generations.md) decision 13.
   - Cleanup is a durable server job on the existing job mechanism.
3. **One cascade table.** For each cause and each dependent kind, it names one action.
   - Causes are a removal, an authority narrowing and an erasure.
   - Dependent kinds include derived records, provenance references of other people, and caches.
   - Each producer of a dependent kind adds its own cells.
4. **Someone else's erasure never deletes the person's own records.** It removes the provenance reference. The person's record stays, with its text.
5. **Tombstones.**
   - The person's `tombstone` table records forgotten items, deleted records, declined suggestions and deleted portable records.
   - `source_tombstone` records a deleted source in a workspace, under workspace tenancy.
   - A forgotten item is not noted again until the person says it again.
6. **An erasure of a record's current revision sends the record to review.** No revision is served until the person acts. An older revision is never promoted automatically.
7. **Two erasures cross owners, and only through `person_row_sweep`** ([ADR 0127](0127-every-record-and-route-resolves-to-concrete-readers.md)).
   - An erased source removes the provenance references of other people that cite it.
   - An erased Slack channel redacts that channel's entries in every person's conversation.
8. **Every new row and every remote copy has a person-data catalog entry with its cascade.** A generated test fails without it.
9. **Erasures and revocations survive a database restore.** A deny intent puts the data on hold at once. The local deletion runs only after the off-database journal holds the fact. A restore reapplies every journaled fact before it serves data. ADR 0140 (proposed in #4867) owns both rules, the journal bucket and the manual path.

### What this changes in other ADRs

- [ADR 0094](0094-a-provider-mirror-changes-only-on-a-definitive-answer-and-a-tombstone-is-not-an-erasure.md) stays. A provider tombstone is not an erasure, and `source_tombstone` is a third kind, for a source that the workspace deleted.
- [ADR 0105](0105-generated-text-carries-a-signed-provenance-record.md) stays. It owns the provenance of generated text. `basis_edge` records which sources a derived record used.

## Consequences

- Good: a deletion is complete in its own transaction. A stale record cannot shape the next answer.
- Good: the person keeps their own words when a team erases a source.
- Good: one test shows a missing cascade cell before the code ships.
- Bad: every read of a derived record checks the generations of its sources.
- Bad: cleanup runs later. Stale rows exist on disk until the job removes them.
- Bad: an instance without object storage for the journal has only the manual recovery path. It cannot run delivery experiments or release research datasets.
- Bad: the cascade table grows with each new dependent kind, and each producer must add its cells.

## Confirmation

Not enforced yet. The implementation adds these tests:

- `CascadeTableTest` fails when a registered dependent kind has no cell for a registered cause.
- An integration test fails when an answer basis that cites an erased entry is not stale on the next read.
- An integration test fails when the deletion of a source entry writes no `DELETED_SOURCE` row in `source_tombstone`.
- An integration test fails when another person's erasure deletes a person-authored record.
- A generated test fails when a new row or remote copy has no person-data catalog entry.

## Revisit trigger

- Lazy validity costs more than 10 % of the time of a hot read.
- Cleanup falls behind by more than one day on a production instance.

## Sources

- Regulation (EU) 2016/679, the [GDPR](https://eur-lex.europa.eu/eli/reg/2016/679/oj), Article 17. Erasure must happen "without undue delay". This is why a revocation is complete in the transaction of its cause. Cleanup only removes rows that already cannot be read.
- EDPB, [Coordinated Enforcement Action on the right to erasure](https://www.edpb.europa.eu/system/files/2026-02/edpb_cef-report_2025_right-to-erasure_en.pdf), 2026, Issue 6. Organisations "should have appropriate procedures to keep track of erasure requests and comply with them on restored systems". This is why erasures and revocations go to a privacy journal that survives a restore.
