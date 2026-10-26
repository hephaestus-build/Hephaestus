# ADR 0062: The item is the review scope

**Status:** Accepted
**Date:** 2026-10-26
**Authors:** Felix T.J. Dietrich

**Builds on:** [ADR 0056](0056-one-practice-model-for-items-and-scans.md). Its item, version and current-claim model stays.

## Context

A practice reviews one item: a pull request, an issue, a submitted review, a document or a scanned file.
Practices select item events through `reviewOn.events`.
In the default catalog, 25 of 52 practices select `opened`, `ready` and `synchronized` of a pull request.

Each pinned item event starts its own review at once.
Only events that still wait for a pin can join another event:

- When a newer version is pinned, the older pending events are withheld with `VERSION_SUPERSEDED`.
- When an event of the same name already holds the version, the new event is withheld with `COALESCED`.

This has three costs:

- Every push runs every selected practice again. Reviews of successive pushes can run at the same time.
- A review of an old version runs to its end. Its results are born ended, and delivery withholds the feedback. The model spend is lost.
- A review does not compare its problems with the previous review of the same item. Feedback can repeat a problem that the author already saw. It cannot say that a problem is gone.

[#1857](https://github.com/hephaestus-build/Hephaestus/pull/1857) added a wait for issue updates: 30 seconds after the last update, at most five minutes.
The cutover to the 1.0 practice model ([#2656](https://github.com/hephaestus-build/Hephaestus/pull/2656)) removed that code.
The operations guide still described the wait until this decision.

Durable workflow engines key work by entity.
They have one writer for each key and a debounce with a maximum wait.
They drop stale work between steps.
Examples are [Temporal Signal-With-Start](https://docs.temporal.io/develop/java/message-passing), [Restate Virtual Objects](https://docs.restate.dev/foundations/services), [Inngest debounce](https://www.inngest.com/docs/guides/debounce) and [DBOS queue deduplication](https://docs.dbos.dev/python/tutorials/queue-tutorial).

The removed achievement system kept a mutable progress row for each person.
It added deltas to that row.
We could not replay, version or erase that state.

## Decision drivers

- One review at the latest version is worth more than several reviews at stale versions.
- We derive item state from rows that do not change, so we can replay and erase it.
- No new tables and no workflow engine for 1.0. The fenced PostgreSQL job queue, its leases and its sweeper stay the execution model.
- Feedback states what changed since the developer last saw it.

## Considered options

1. **One review per event (current).** Rejected. It spends on stale versions and repeats feedback.
2. **A state table for each item and practice, or a workflow engine.** Rejected for 1.0.
   The state already exists in rows that we keep: `item_event`, `review_item`, `result` and `problem`.
   An engine adds a second source of truth for durability.
3. **The item as the key over existing rows: quiet window, supersede, lineage.** Chosen.

## Decision

The item is the scope and the serialization key.
Item state is these rows:

- the item event ledger,
- the review membership,
- the current result for each practice, item and developer,
- the problem lineage.

Practice scripts read item state. They never write it.

**Quiet window.** An item event waits before it starts a review.
It stays `WAITING`, and `retry_at` holds the end of the wait.
A newer event of the same item moves `retry_at` and withholds the older waiting events with `COALESCED`.
A cap from the first waiting event limits the wait, so a busy item still gets a review.
When the wait ends, one review runs at the latest version.
It runs the union of the practices that the joined events select.
These events never join a later push: `merged`, `closed` and a submitted pull request review.
The [operations guide](../admin/practice-review-operations.mdx) will own the window and the cap when this ships.

**Supersede.** At the start of each practice, the runner asks the gateway if the pinned item version is still current.
If it is not current, the practice ends with the runtime non-result `REPLACED`.
The runner starts no session for it.
A review whose results have all ended composes no feedback ([ADR 0061](0061-review-seals-then-feedback-composes.md)).

**Lineage.** Each problem of a sealed result has a lineage state: New, Unchanged, Updated or No longer present.
The comparison uses the previous comparable result of the same practice on the same item.
One rule applies to every producer: No longer present needs complete proof at a changed version.
A missing problem in an incomplete or failed review proves nothing.
`problem.lineage_id` links a problem to its earlier form.
Feedback on the work speaks about New and Updated problems.
The practice page and the conversation can show all four states.

**Input digest (after 1.0).** A later item version can reuse a claim without a sandbox when nothing that the practice reads has changed.
The key is a digest of the practice version, the frozen method, the agent image, the declared sources as captured and the previous comparable claim.
Reuse needs a complete dependency record from the host ([ADR 0056 § Dependency-based scan reuse](0056-one-practice-model-for-items-and-scans.md#dependency-based-scan-reuse)).
Reuse stays off until the host refuses reads outside the declared sources.

## Consequences

- A pull request gets fewer reviews. The quiet window delays the first feedback by up to the window.
- A review can end Partial with `REPLACED` practices. This is correct, because their version is gone.
- One migration adds the cap column to `item_event` and a waiting reason to `ck_item_event_reason`.
- One migration adds `problem.lineage_id` and the lineage state.
- Practice authors keep `reviewOn.events`. The item kind owns which events can join.
- A practice that needs a timer can use an item event with a future `retry_at`. An example is "14 days after opened, unless closed". We add it only when a practice needs it.

## Revisit trigger

Revisit when an item needs more than one active review at a time.
Revisit when the measured delay of the quiet window harms the feedback loop.
Revisit the workflow engine option when a review step needs durable state that one row for each practice cannot hold.
A review of a whole project, not of one item, is out of scope for 1.0. It needs its own ADR after 1.0.
