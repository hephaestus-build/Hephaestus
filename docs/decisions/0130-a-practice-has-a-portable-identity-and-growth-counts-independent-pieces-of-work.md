# ADR 0130: A practice has a portable identity, and growth counts independent pieces of work

**Status:** Proposed
**Date:** 2026-11-23
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0056](0056-one-practice-model-for-items-and-scans.md) (a practice version gains an identity and a comparability class)

**Builds on:** ADR 0124 (proposed in #4866, practices are the unit), [ADR 0085](0085-an-observation-has-one-of-four-outcomes-and-standing-reads-the-newest-decision.md) (four outcomes), [ADR 0118](0118-a-workspace-keeps-its-practices-in-one-repository-and-a-trial-reviews-a-draft-practice-without-an-observation.md) (a workspace keeps its practices in one repository)

**Relates to:** [ADR 0129](0129-learning-travels-as-the-persons-own-words-to-authorized-workspaces.md) (closed fields travel)

## Context

On main, each workspace has its own practice versions ([ADR 0056](0056-one-practice-model-for-items-and-scans.md), [ADR 0118](0118-a-workspace-keeps-its-practices-in-one-repository-and-a-trial-reviews-a-draft-practice-without-an-observation.md)).
Two workspaces that adopt one catalog practice have two unrelated rows.
Nothing says that they are the same practice, or that an edit changed what the practice judges.

Growth that travels with the person needs that fact.
Take a point for "say how to verify a change" in one team. It must count in the next team only if both teams judge the same thing.

Growth must also count real progress.
One pull request that is reviewed five times is one piece of work.
Help must fade as the person no longer needs it, and come back when they ask.

## Decision drivers

- A practice keeps its identity across workspaces, renames and versions.
- An edit that changes the judgement starts a new class, so old and new points never add up.
- Growth counts independent pieces of work, never observations or use.
- Help fades on evidence and never fades when the person asks for it.

## Considered options

1. **The practice name is the identity.** A rename breaks the history, and two teams can give one name to two different practices.
2. **An origin field in the practice file, or an admin who asserts that two practices are the same.** An admin can assert it wrongly, and nothing proves it. A field can survive an edit that changed the judgement.
3. **Count each observation.** A pull request that is reviewed five times counts five times. Growth then rewards more reviews, not better work.
4. **A point for every outcome and every feedback mode.** Main has four outcomes, and Could not determine says nothing about the person. On request is the person's own choice to get help, not a test.
5. **A domain-qualified identity, a comparability class from the review digest, and one point for each piece of work (chosen).**

## Decision

Option 5.

1. **Catalog metadata holds the identity.** It holds the domain, the key, the comparability classes with their digests, related practices, former keys and a retired mark. The loader and a build check read it.
2. **Each practice version stores its identity.**
   - `practice_version.identity_key` has the form `<domain>/<slug>`, with a `comparability_class`.
   - Both are set at insert and never change, except one change from NULL.
   - The upgrade fills them from the bundled catalog.
3. **The comparability class follows the judgement.** The review digest already hashes the fields that shape the judgement. An edit that changes that digest gets a new class.
4. **A local practice has no portable identity.** Its key is `local/<workspace>/<practice>`. It never travels.
5. **A related practice never changes a count.** It is a link for the reader, not a shared total.
6. **Growth points count pieces of work.**
   - One point exists for each person, canonical piece of work and portable practice.
   - Mirrors of one item count once.
   - Only Met and Not met make a point. Off and On request observations make none.
7. **Each point records what made it:** a review, a self-report or an import. Self-reports and imports never count in totals.
8. **Growth points belong to the person.** They survive leaving and purge, under the export policy of their workspace ([ADR 0129](0129-learning-travels-as-the-persons-own-words-to-authorized-workspaces.md)).
9. **The help mode for each practice** is `EXPLAIN`, `HINT`, `ASK_FIRST` or `STEPPED_BACK`.
   - Growth computes it. A goal's own help preference overrides it.
   - The UI shows it as words, never as a level.
10. **Fading follows the evidence that the domain declares.** In software engineering, that is three independent reviewed pieces of work. Help on request never fades.
11. **Stepping back.** When a practice holds, Hephaestus says once, with the evidence, that it steps back. It offers to raise the bar.

### What this changes in other ADRs

- [ADR 0056](0056-one-practice-model-for-items-and-scans.md) stays. Each practice version gains `identity_key` and `comparability_class`.
- [ADR 0085](0085-an-observation-has-one-of-four-outcomes-and-standing-reads-the-newest-decision.md) stays. Growth reads its outcomes and adds no fifth outcome.
- The practice file format documents the `identity` metadata when this ADR is accepted.

## Consequences

- Good: growth means the same thing in every team that uses one catalog practice.
- Good: an edit that changes the judgement cannot inflate old growth.
- Good: more reviews of one piece of work never look like more growth.
- Bad: a workspace that writes its own practice gets no portable growth for it.
- Bad: a small edit to a judgement field starts a new class. The person's count for that practice then starts again.
- Bad: catalog authors must keep identities, related links and former keys correct. A build check catches only conflicts.

## Confirmation

Not enforced yet. The implementation adds these tests:

- A build check fails on two catalog entries with one key and different classes.
- A unit test fails when an edit that changes the review digest keeps the comparability class.
- `OnePointPerWorkTest` fails when one person, piece of work and practice get two points at projection, transfer, absorption or import.
- An integration test fails when an On request seal or an Off observation writes a growth point.
- A drill seed fails when existing practice versions have no identity after the upgrade.

## Revisit trigger

- Teams edit catalog practices so often that most points start new classes.
- A second domain needs a fade basis that pieces of work cannot express.

## Sources

- Wood, Bruner and Ross, [The role of tutoring in problem solving](https://doi.org/10.1111/j.1469-7610.1976.tb00381.x), Journal of Child Psychology and Psychiatry, 1976. A tutor gives support where the learner needs it and withdraws it as the learner becomes able. This gives the help mode and fading.
- Kalyuga, Ayres, Chandler and Sweller, [The expertise reversal effect](https://doi.org/10.1207/S15326985EP3801_4), Educational Psychologist, 2003. Guidance that helps a novice can hinder a more expert learner. This is why help steps back when a practice holds.
