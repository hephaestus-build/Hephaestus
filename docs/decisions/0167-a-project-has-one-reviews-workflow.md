# ADR 0167: A project has one Reviews workflow

**Status:** Proposed
**Date:** 2026-11-27
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0056](0056-one-practice-model-for-items-and-scans.md), [ADR 0160](0160-a-sealed-review-hands-off-to-the-mentor.md), [ADR 0165](0165-a-team-reviews-immutable-submitted-versions-within-its-own-scope.md)

**Builds on:** [ADR 0127](0127-every-record-and-route-resolves-to-concrete-readers.md)

**Relates to:** [ADR 0162](0162-a-team-owns-its-growth-record-in-its-workspace.md)

## Context

A team project gets practice feedback in two ways that never meet.
A repository scan reviews the files of a default branch, and only admins can request, schedule, read and export it.
A team submits versions of its work for review (ADR 0165).
Both already write the `review` row: a scan has `kind = SCAN`, and its origin is `REQUEST` or `SCHEDULE`.
They differ in who starts a review, who reads it, and how a reader compares two reviews.
The real constraint is that a review's readers are fixed when the review is created.
An admin who configures reviews must not read a team's content because of that role.

## Decision drivers

- The readers of a review follow from it at creation and never widen.
- One request, one record and one read model for every reviewed item.
- Missing evidence or coverage never reads as a result or as improvement.
- No history moves: an existing review keeps its id and its meaning.
- The production review capacity is shared, and this decision does not increase its load.

## Considered options

1. **Keep scans as an admin product, and add team reviews beside them.** Two request paths, two histories and two comparison rules must agree. A team cannot review its repository.
2. **One navigation item over the two workflows.** It looks like one workflow, but each half keeps its own record, readers and comparison. Missing coverage then reads differently on each side.
3. **One request, one record and one read model, with readers fixed by an audience (chosen).** The scan inventory, reuse, budget, comparison and SARIF stay capabilities of a review of a repository. This costs an expand migration, a repository mapping and a cutover of the scheduler.

## Decision

1. **One request.** Each review that a review setup starts enters one server command: `ReviewRequest(setupId, item, origin, requestKey, requestedBy)`.
   - `item` is the reviewed item at a version: a submitted version (`SubmittedVersion(versionId)`) or a repository at a commit (`RepositoryCommit(repositoryItemId, ref, commit)`).
   - Without a `commit`, the server pins the head of the monitored default branch at creation. With a `commit`, the server refuses a commit that is not that head.
   - `origin` is `REQUEST` or `SCHEDULE`. The HTTP route takes only `REQUEST`. Only the review scheduler writes `SCHEDULE`.
   - The server sets `requestedBy` from the signed-in account. It is never a body field. A `SCHEDULE` review has no requester.
   - The same `requestKey` returns the same review. The server refuses the same key with another input. The scheduler derives its key from the setup and the due time, so one due time gives one review.
   - The request has no audience and no budget. Both come from the setup.
   - A pull request or an issue at a revision keeps its own review, which an event starts (ADR 0056). It does not enter this request in 1.0.
2. **One record.** The existing `review` row gains `setup_id`, `version_kind`, `audience` and `audience_team_id`.
   - `version_kind` is `REPOSITORY_COMMIT`, `SUBMITTED_VERSION` or `ITEM_REVISION`.
   - `audience` is `OPERATIONAL`, `TEAM` or `PERSONAL`. `audience_team_id` is set only when the audience is `TEAM`.
   - The server writes all four values once, when it creates the review. Nothing changes them later.
   - A review without these values is older than this decision. It reads as `OPERATIONAL` if it is a scan, and as `PERSONAL` if not. No rule gives it a team.
3. **The audience decides the readers, at every read** (ADR 0127).
   - `OPERATIONAL`: the workspace admins and owners, as for a scan today.
   - `TEAM`: the current members of the team project. A removed member reads nothing.
   - `PERSONAL`: the readers of the person's work, as today.
   - An admin outside the team reads the metadata of a `TEAM` review: its status, coverage, cost and counts. A request for its content gets the same answer as a review that does not exist. Content is the observations, problems, evidence text, discussion and guidance.
   - An export holds only what its reader may read.
   - A `TEAM` review of a repository needs an admin to map the repository to the team project first (`team_project_repository`). When the mapping is removed, later reviews cannot be `TEAM` reviews. No earlier review changes.
4. **One read model.** The history, detail and comparison of each review that a setup started extend `ReviewSummaryDTO` and `ReviewDetailDTO`. The schedule of a setup extends `ReviewScheduleDTO`. No second DTO family exists for an audience or for a kind of item. A contract test with a JSON fixture holds the field shapes.
5. **Coverage counts the planned pairs of each practice as reviewed, reused, withheld or not reviewed, for each scope.**
   - A practice has one scope: a file, the repository or the submitted version. A setup for a repository can select practices of both of its scopes. A repository-scope practice reviews the repository item at the commit, with the evidence pinned for it.
   - File observations never add up to an observation about the repository.
   - A review that closes with a planned pair not reviewed is Partial. It shows its coverage, and it never reads as complete.
   - The server pins evidence from outside the commit once, when it creates the review, and records when it read it. Examples are CI runs and a merge-rule snapshot. When a source is missing or incomplete, the criterion is **Could not determine**, never **Not met**.
6. **A comparison names three independent changes against its baseline.** The baseline is the newest earlier closed review of the same setup and item.
   - `version`: same, or changed (another commit, or another submitted version).
   - `evidence`: same, changed or unknown.
   - `setup`: same, or changed (another practice version or method).
   - Two reviews of one commit with different pinned evidence read "Same commit, evidence changed", never "Code changed".
   - A problem is Absent only when both reviews judged its pair from complete reads. When the newer review did not review a pair, the pair is Not reviewed. Missing coverage is never improvement.
7. **At seal, the audience chooses one producer** (ADR 0160 decision 1).
   - `OPERATIONAL`: `OBSERVATIONS`. No composer runs, and no feedback goes out. The report is its view.
   - `TEAM`: `TEAM` for a `REQUEST` review when the conditions of ADR 0165 hold, and `OBSERVATIONS` otherwise. A `SCHEDULE` review never composes.
   - `PERSONAL`: the producers of ADR 0160 decision 1, as today.
8. **No history moves.** An existing scan keeps its id, report, comparison, SARIF and retention as an `OPERATIONAL` review. No old review gets team readers, a discussion or team memory.
9. **One trigger writer.** The review scheduler is the only writer of `SCHEDULE` reviews, and the scan driver only advances reviews. Until the cutover, the recurring check opens scans as it does now, and no setup schedules a repository.

### What this changes in other ADRs

- ADR 0056: a scan is the review of a repository at a commit. Beside its file practices, it can hold repository-scope practices.
- ADR 0160: decision 1 gains the producer choice by audience of decision 7.
- ADR 0165: decision 4 no longer names a pinned repository commit as a route to a submitted version. A repository at a commit is its own kind of version. Decision 10 lets a setup select practices for its item and for the members of the item.

### What this does not decide

- The project page, its routes and its navigation.
- Capacity. One active repository review per workspace stays (ADR 0056). The step that serves both kinds of item decides with the production owners how a second request waits.
- New CI or merge-rule connectors. Without one, the CI practice of a repository reads its configuration, and the rest is **Could not determine**.
- Pull requests and issues in the project history, and views across teams.
- How widely Team Mentor is available in stable 1.0.

## Consequences

- Good: one history holds a team's repository and its submitted work, and the readers are fixed at creation.
- Good: the scan inventory, reuse, budget, comparison and SARIF do not change.
- Good: a scheduled and a requested review of one setup are two reviews in one history, each with its origin.
- Bad: `review`, a large table, gets an expand migration now and a contract step later.
- Bad: admins cannot read the content of the team reviews that they configure.
- Bad: the cutover of the scheduler needs its own compatibility and rollback proofs.
- Bad: a repository-scope practice is new execution inside a scan.

## Confirmation

Not enforced yet. Each step adds its check:

- The contract: a test serializes a `REQUEST` and a `SCHEDULE` review of a repository commit and of a submitted version into the extended DTOs, and compares them with a JSON fixture.
- The expand migration: a test that an older review reads as `OPERATIONAL` or `PERSONAL`, never `TEAM`.
- The parity proof of #5464.
- The request and the read model: an authority table as tests. It covers each route for a member, a removed member, an admin outside the team and a guessed id.
- The cutover: exactly one scheduled review for each due time, and a rollback.

Until each check exists, a reviewer compares changes to `review`, the scheduler, the scan routes and the review DTOs with decisions 1 to 9.

## Revisit trigger

A reviewed item needs readers that its audience cannot express, or a second writer of scheduled reviews becomes necessary.

## Sources

- [RFC 9110 § 15.5.4](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.4): a server that hides that a forbidden resource exists may answer 404. Decision 3 gives the content of a team review that answer.
- [Stripe, Idempotent requests](https://docs.stripe.com/api/idempotent_requests): a repeated key returns the first result, and a repeated key with other parameters is an error. Decision 1 takes both rules for `requestKey`.
