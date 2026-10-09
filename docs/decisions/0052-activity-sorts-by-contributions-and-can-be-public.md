# ADR 0052: Workspace activity sorts people by plain counts, and a workspace can publish it

**Status:** Accepted
**Date:** 2026-10-09
**Authors:** Felix T.J. Dietrich
**Supersedes in part:** [ADR 0045](0045-activity-counts-work-and-never-ranks-people.md). Its *Update — 2026-10-09* lists the parts.

## Context

[ADR 0045](0045-activity-counts-work-and-never-ranks-people.md) removed the leaderboard in [#2260](https://github.com/hephaestus-build/Hephaestus/pull/2260).
It also ruled out every order of people by a count, and every public read.
Its revisit trigger names "members asking to sort by it".
That trigger fired.
The lead of a large open-source project on hephaestus.build asked for three things that the old leaderboard gave:

- A ranking of the people who contribute.
- A free date range.
- A public view that needs no sign-in.

Most of that data is already public.
For each public repository, GitHub shows a contributors graph of the top 100 contributors, ordered by commits [1].
A GitHub profile counts opened issues, proposed pull requests and submitted reviews [2].
A table that does not sort also does not stop a comparison.
In a workspace of some hundred people, the reader compares names by eye and cannot find the active people.

The evidence that ADR 0045 cites is still valid.
A metric that becomes a target is gamed [3].
A weighted score and a streak change how people work [4].
Activity counts are context, not a measure of a person [5].
This ADR keeps those harms out with fewer mechanisms than a ban on sorting.

## Decision drivers

- Every number stays checkable. A count opens the list of the work that it counts.
- No weights and no points. A total adds pieces of work, and each piece counts once.
- What helps people do their work stays unchanged: **Needs you**, covered requests, and team requests.
- Public data goes only where the provider already publishes the same work, and each person can leave.
- A hidden person leaves no trace. They are not in a total, and no position has a gap for them.
- Practice data never enters an order of people or a public page.
- The smallest mechanism: one aggregate response, sorted in the browser.

## Considered options

The order of people:

1. **Names only, as in ADR 0045.** Rejected. The revisit trigger fired, and a long list by name answers no question that a maintainer asks.
2. **Every count column sorts, the default is by name.** Rejected. The first view still answers no question.
3. **Every count column sorts, the default is by Contributions.** Chosen.
4. **A weighted score, as the old leaderboard had.** Rejected again, for the reasons in ADR 0045.

The default measure:

1. **Commits, as the GitHub contributors graph uses.** Rejected. A squash merge removes them, and many small commits increase them. The unit of Hephaestus is reviewed work.
2. **Pull requests opened.** Rejected. It hides the reviewers.
3. **Comments added to the total.** Rejected. A comment is easy to add for a count. Comments stay in the drill-down.
4. **Contributions: pull requests opened, pull requests reviewed, and issues opened.** Chosen. Each part is one piece of work with a link.

The position number:

1. **No number, only the order.** Rejected. Readers count rows, and a tie is not visible.
2. **Dense ranking (1, 2, 2, 3).** Rejected. It hides how many people are in front.
3. **Standard competition ranking (1, 2, 2, 4) [6].** Chosen.

The public view:

1. **Members only, as in ADR 0045.** Rejected. An open-source project wants to show who contributes, and the provider already shows it.
2. **A public read of the whole workspace.** This is the generic anonymous read behind `isPubliclyViewable`. Rejected. It opens every `GET` endpoint of the workspace, and a person cannot leave.
3. **Opt-in for each person.** Rejected. Most contributors to an open-source project never sign in, so the page stays almost empty.
4. **Opt-out for each person, on one narrow page, behind an instance switch and a workspace switch.** Chosen.

The notice to the people on the page:

1. **An email before go-live, with a notice period.** Rejected. Most people on the page have no account and no address in Hephaestus. A notice period adds a state and a delay.
2. **A banner on every page.** Rejected. People close a banner without reading it, and it adds a state to every page.
3. **A workspace onboarding step, the same switch in User settings, and a notice on the public page.** Chosen.

## Decision

### Sorting

- Workspace activity has one row for each person.
  Every number column sorts.
  The browser sorts one aggregate response, which holds some hundred rows.
- The default sort is **Contributions**, descending.
  A tie sorts by display name.
- **Contributions** is the sum of three counts in the selected range:
  - Pull or merge requests that the person opened.
  - Pull or merge requests that the person reviewed. Each one counts once, however many reviews the person gave. A review of the person's own work never counts.
  - Issues that the person opened.
- No other total exists, and no count has a weight.
- When the table sorts by a number column, each row shows a **position**.
  Positions use standard competition ranking (1, 2, 2, 4).
  They come from the counts that the table shows.
  A name search hides rows but does not renumber them.
- Provider bot accounts are never people.
  A workspace admin can mark a machine user account as automation.
  Automation is not in the people table, in a total, or in a position.

### What stays from ADR 0045

- Every count opens its list, and a count and its list follow the same rules.
- One metric for each chart, on its own scale. The drill-down shows weekly bars, never a daily calendar.
- The timeline groups events by the work.
- **Needs you**, covered review requests, and team review requests are unchanged.
- Outcomes belong to the author. Reviews of one's own work and bot work do not count.
- Activity is always on for members, and it has no workspace switch.

### Practices stay out

Standings, practices, observations and feedback never sort people.
They never appear on a public page.
[ADR 0051](0051-practices-across-the-workspace-counts-developers.md) records the same limit for **Practices across the workspace**.

### The public activity page

Three switches must all be on:

1. The instance setting **Allow public activity pages**. It is off by default. hephaestus.build turns it on.
2. The workspace admin's switch for the workspace, in the workspace settings. A confirmation dialog states what becomes public. Both directions take effect at once.
3. The account-wide switch **Show me on public activity pages**. It is on by default. It works at once.
   For a person without an account, an objection through the privacy contact takes its place.

The page shows only this data:

- Work in the workspace's public repositories. The server checks the repository visibility at query time.
- Pull or merge requests, reviews and issues, as counts, positions and links to the work.
- Humans only. Members and outside contributors to those repositories.

It never shows practices, feedback, observations, Slack, Outline, or AI review content.
It never shows a hidden person or an erased person.

A hidden person leaves the page and every total on it.
The page computes positions after it removes hidden people, so no gap shows where a hidden person was.
Workspace admins see how many people are hidden, never who.
A person without an account can object through the privacy contact.
The operator then applies the same hide to that person's verified provider identity.

### The notice

There is no email, no banner and no notice period.

- A workspace with a public activity page shows a workspace onboarding step at the first visit of a signed-in person.
  The step says that the page is public.
  It shows what is on the page.
  It offers **Show me** and **Hide me** with equal weight.
- **User settings** has the same switch.
- The public page says what it shows.
  It also says: "On this page? Sign in to hide yourself."

### Access

- A signed-out visitor at the workspace root sees the public activity page if the workspace has one.
  Otherwise, the visitor sees the sign-in page.
  An unknown workspace and a private workspace give the same response, so nobody can probe which workspaces exist.
- One dedicated anonymous endpoint serves the page, with its own DTO.
  A narrow `public_activity` state replaces `isPubliclyViewable`.
  The generic anonymous read in `WorkspaceContextFilter` and the public listing in `findAccessibleWorkspaces` go away.
  An architecture test fails for each anonymous endpoint that is not on an explicit allowlist.
- Only that endpoint sends `Cache-Control: public, max-age=60`.
  It is rate-limited.
  It sends `noindex` unless an admin turns on **Allow search engines**.
- A workspace goes public only after the activity data runbook's comparison with the provider passes for its repositories.
- The docs advise course workspaces to stay private. Hephaestus does not enforce it.

## Consequences

- A sorted table with positions is a leaderboard in function.
  This ADR accepts that.
  It keeps out what made the old one harmful: weights, points, leagues, levels, streaks, resets and a digest.
  The product still does not use the words *leaderboard*, *rank* or *score* for it.
- Members can game Contributions with many small pull requests or reviews.
  Each review counts once for each pull request, and each count opens its list.
  Thus, the gaming is visible to every reader.
- The public sees who did the most work in public repositories.
  The [DPIA pre-screen](../admin/dsms/dpia-prescreen.md) reassesses this audience.
  The TUM data-protection coordinator confirms it before a TUM workspace goes public.
- Hiding is from the public, not from the workspace.
  Members still see a hidden person, so an admin can find who is hidden by a comparison.
- A self-hosted instance publishes nothing until its operator turns on the instance setting.
- The anonymous surface shrinks.
  One endpoint replaces the generic read of every workspace `GET` endpoint.
- A member who does not open the workspace gets no individual notice before the first publication.
  The privacy notice and the page notice inform people in public from the first moment.
  The TUM coordinator decides if this meets Art. 14(3)(c) GDPR.

## Revisit trigger

- Evidence that Contributions is used as a target.
  An example is a rise of trivial reviews.
  Another example is a report that a team uses it in a performance review or a grade.
- A request to weight the counts, or to add comments or practices to the total.
- A complaint from a person on a public page.
- A full DPIA that does not confirm the reassessment for the public audience.

## Sources

1. GitHub Docs, *Viewing a project's contributors*: <https://docs.github.com/en/repositories/viewing-activity-and-data-for-your-repository/viewing-a-projects-contributors>
2. GitHub Docs, *Profile contributions reference*: <https://docs.github.com/en/account-and-profile/reference/profile-contributions-reference>
3. DORA, *DORA's software delivery metrics*: <https://dora.dev/guides/dora-metrics/>
4. Moldon, Strohmaier and Wachs, *How Gamification Affects Software Developers*, ICSE 2021: <https://arxiv.org/abs/2006.02371>
5. Forsgren et al., *The SPACE of Developer Productivity*, ACM Queue 19(1), 2021: <https://queue.acm.org/detail.cfm?id=3454124>
6. Wikipedia, *Ranking*, standard competition ranking: <https://en.wikipedia.org/wiki/Ranking#Standard_competition_ranking_(%221224%22_ranking)>
