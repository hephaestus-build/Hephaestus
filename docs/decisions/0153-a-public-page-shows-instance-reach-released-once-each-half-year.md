# ADR 0153: A public page shows instance reach, released once each half-year

**Status:** Accepted
**Date:** 2026-11-24
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0125](0125-hephaestus-is-never-a-performance-instrument.md) decision 14, which now also covers a public page.

**Relates to:** [ADR 0125](0125-hephaestus-is-never-a-performance-instrument.md) option 3, usage dashboards

## Context

People who decide whether to adopt Hephaestus ask whether anyone uses it.
Today an instance cannot answer without a staff view or a private message.

[ADR 0125](0125-hephaestus-is-never-a-performance-instrument.md) decision 14 sets the disclosure rule for figures that staff, the steward or a research recipient sees.
It names no anonymous reader.
Its option 3 declines usage dashboards, because they measure how a person or a team uses Hephaestus.

A public reader can be anyone, also an employer.
The reader can compare every release with every other release, and can bring outside knowledge.
So the public rules must be stricter than the staff rules where they differ.

## Decision drivers

- A reader sees that the instance is in real use.
- No figure is about one person or one workspace, and no two releases overlap or change.
- A published figure never changes. A correction can only hide it.
- The operator decides whether the page exists.
- The cost of a request does not grow with the review data.

## Considered options

1. **No public figures.** The adopter has no evidence of use, and asks the operator for a private view.
2. **A live page with rolling 30-day totals.** Two reads a day apart show one workspace that joins or leaves. Differencing breaks the floor.
3. **Monthly releases with a trust funnel and shares of observation outcomes.** More releases give more differences. A share of Met and Not met reads as a grade of every developer. One large workspace exposes its own mix.
4. **Reach only, in fixed half-years, released once, with floors, rounding and a dominance rule (chosen).**

## Decision

Option 4.
The server applies each rule before a figure leaves it.
A figure that fails a rule is stored as not published, never as zero.

1. **A public page may show instance figures.** Decision 14 of ADR 0125 now also covers the page **Hephaestus in numbers**, with the rules below. The rules hold for every reader of the page, signed in or not. Staff can read the page like anyone else. A staff role gives no other usage view.
2. **Reach only.** The page defines exactly five figures. A released period shows them only if it passed the floors of decision 8. Each counts records of every workspace of the instance. A review with origin `TRIAL` adds to no figure: the server leaves it out before it counts.

   | Figure                                 | Counts                                                                                                                                                                          | Event time                                                     |
   | -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
   | **Workspaces**                         | Distinct workspaces with at least one `review_practice` row                                                                                                                     | `review.created_at`                                            |
   | **Developers whose work was reviewed** | Distinct accounts in `result.developer_id`, once each across workspaces. A result with no developer adds no one.                                                                | `result.recorded_at`                                           |
   | **Reviewed work**                      | Distinct `review_item.item_id` values where `review_item.state` is `JUDGED` or `REUSED` and `practice.kind` is `scm.pull_request`, which holds pull requests and merge requests | `review.created_at` of the review that holds the `review_item` |
   | **Practice reviews**                   | `review_practice` rows, one for each practice in a review, whatever its status                                                                                                  | `review.created_at`                                            |
   | **Practice feedback delivered**        | `feedback` rows in state `DELIVERED`                                                                                                                                            | `feedback.delivered_at`                                        |

   Reuse can thus count work in a later period than the observation that it reuses.

3. **Fixed half-years in UTC.** A period starts at 00:00 UTC on 1 October or 1 April. It ends, excluded, six months later. Periods never overlap.
4. **Released once.** A period becomes due 14 days after its end. The server then computes it once, from the event time of each record. It never computes a stored period again. A record that arrives after the release is never counted.
5. **The first release has history.** Whenever the page is on and no period is stored, the server looks for the first release. It finds the earliest period that still holds a practice review that is not a trial.
   - If no such period exists, or it is not due yet, the server stores nothing. It looks again at the next run.
   - Otherwise it releases that period and each later period that is due.
   - If erasure removed older reviews, the history starts later.
   - After the first release, the server releases each due period once.
6. **The server computes only while the page is on.** While the page is off, the server releases no period. When the page is on, each run releases every due period after the latest stored one, in order. From the first stored period onward, releases have no gaps.
7. **Late releases count what exists.** The source tables record event time, not arrival time. A late release counts the records that meet decision 2 and still exist when the server computes the period. Retention and erasure can remove older rows first. So a late figure can be lower than a timely one.

8. **Floors.** A period publishes figures only if its raw counts hold at least 5 workspaces and at least 5 developers. Otherwise the page shows "Not enough activity to publish." for the period, and no figure.
9. **The floor is for each period, not for each figure.** This is an exception to the floor for each cell in ADR 0125 decision 14. Only the figure Developers counts people, and the period floor covers it. The other four figures count workspaces, work, reviews and feedback. Rounding and the dominance rule protect them.

   The unanimous-cell rule does not apply, because no figure shows an outcome. The ban on overlapping cuts holds, because every figure covers the whole instance.

10. **Rounding.** The server rounds each count down. Below 1,000, it rounds to a multiple of 10. From 1,000, it rounds to a multiple of 100. A count that rounds to zero, such as 5 to 9, is not published. This version publishes no shares.
11. **Dominance.** For each figure, the server compares the largest part of one workspace with the raw total of the instance. A part counts the same rows and the same distinct key as the total, but only in that workspace. For Developers, a person counts in each workspace where they have a result in the period. In the total, each person counts once.

    If the largest part is more than a third of the total, the figure is not published. Exactly a third passes this check. The figure can still round to zero.

12. **One wording for hidden figures.** In a period that passed the floors, every hidden figure reads "Not published". The page never says why.
13. **No ranking and no names.** The page names no workspace and no person. It orders nothing by a value. Periods appear in calendar order. The five figures appear in the order of decision 2.
14. **These figures are cut.** A later ADR must allow each one before the page shows it:
    - The trust funnel, from practice reviews to delivered feedback.
    - Shares of observation outcomes, such as Met and Not met.
    - Shares of feedback responses, such as Addressed and Disagree.
    - Any count of conversations or turns, and any separate count of feedback delivered in conversation. The total of delivered feedback includes every destination.
    - Any split by provider, practice or model, and a list of top practices.
    - Any figure for one workspace or one person.

15. **Withdrawal.** An instance admin can withdraw a released period, or one figure of it. A withdrawal only hides.
    - The server stores each withdrawal in the instance settings, apart from the release row, for the life of the instance.
    - The auth event log also records each withdrawal. Only staff with audit access read the log.
    - A withdrawal is final, because ADR 0125 decision 14 lets a correction only suppress a released figure. This is a deliberate exception to the rule that every state can be undone.
    - The page keeps the period label and shows "Not published" for each withdrawn figure. A period that failed the floors keeps "Not enough activity to publish."
    - A reader with an earlier copy can still see that a figure went away.
16. **Off by default.** An instance admin turns the page on or off in an audited instance setting. Before an admin saves a choice, the property `hephaestus.public-usage.enabled` decides. Its default is `false`. While the page is off, its routes answer 404.
17. **Every origin read checks.** Each read that reaches the server checks the setting and the withdrawals. A revalidation is such a read.
    - A successful response carries `Cache-Control: public, max-age=300, must-revalidate`.
    - It also carries a strong ETag over the bytes of the full response, after the withdrawals.
    - An error carries `Cache-Control: no-store`. The 404 of a page that is off is such an error.
    - A request that starts more than 5 minutes after a change of the setting or a withdrawal shows the change. A page that is already open keeps what it shows.
18. **Stored once, served from the rows.** A global table `usage_release` holds one insert-only row for each released period. The row holds only the public status of the period and its published figures. The server never stores a raw count. A read never computes a figure. A withdrawal never changes the row.

## Consequences

- Good: an adopter sees that the instance is in real use. At the first release, the history starts with the earliest period that still holds a practice review. Trials do not count.
- Good: no figure is about one person or one workspace, and two releases never overlap or change.
- Good: a release costs one computation each half-year. A read uses one stored row for each period that it returns.
- Bad: a reader with outside knowledge can still learn something from two releases. Suppose that a reader knows that the same 19 developers had work reviewed in both periods. Suppose that the reader also knows that only one other developer could have had work reviewed. A change from 10 to 20 then shows that this developer had work reviewed.
- Bad: a late release counts records with a backdated event time that a timely release misses. It also misses rows that retention or erasure removed before it ran.
- Bad: under normal operation, the newest released period ended up to six and a half months ago.
- Bad: a small instance shows "Not enough activity to publish" for every period.
- Bad: a withdrawal cannot be undone. An admin who withdraws a figure by mistake hides it for good.
- Bad: a restore from a backup that is older than a withdrawal loses the withdrawal. The admin must withdraw the figure again.

## Confirmation

Not enforced.
The implementing pull request adds the checks below, and its review checks that each one exists:

- UsageDisclosureTest covers the floors and rounding at 9, 10, 999 and 1,000. It covers a count that rounds to zero. It covers dominance at exactly a third and just above it.
- UsageReleaseServiceTest checks which periods the server releases. It releases no period before its due time, and nothing while the page is off. The next run after that releases every due period after the latest stored one, in order. With no period stored, the next run makes the first release, also after the page was off. With no reviews, or with trials only, it stores nothing.
- UsageReleaseIntegrationTest counts each of the five figures from real rows. It counts a developer of two workspaces once, and leaves out trials. It fails when a period is computed again, released twice, changed, deleted or overlaps another.
- PublicUsageControllerIntegrationTest checks that, while the page is off, each route returns 404 with `Cache-Control: no-store`. It fails when a route shows a withdrawn figure. It checks the cache headers and the audit records. The test withdraws a visible figure and sends the earlier ETag. The server returns 200 with a new ETag. The test then turns the page off and sends that latest ETag. The server returns 404 with `no-store`.
- `ResponsePeopleCountTest` requires the count of developers to be a `ShownCount`.
- The implementing pull request adds the admin guide page **Publish Hephaestus in numbers** and links it from this ADR. The page describes how to turn the page on and how to withdraw. It names the delay of 5 minutes.

## Revisit trigger

- Adopters ask how often Hephaestus holds back feedback. A trust funnel then needs its own ADR.
- Most periods of a production instance read "Not enough activity to publish" or "Not published".
- A data protection authority or a works council objects to a public figure.

## Sources

- Artemis, [public usage statistics](https://github.com/ls1intum/Artemis/blob/develop/src/main/java/de/tum/cit/aet/artemis/admin/service/PublicUsageStatisticsService.java): totals for each semester, and a course counts only with at least 5 students. Hephaestus also uses half-years. It freezes each one instead of refreshing it, because a refreshed total lets a reader see a difference.
- Microsoft, [Viva Insights privacy settings](https://learn.microsoft.com/en-us/viva/insights/advanced/setup-maint/privacy-settings#minimum-group-size): a minimum group size of at least five. The floor of decision 8 uses the same number.
