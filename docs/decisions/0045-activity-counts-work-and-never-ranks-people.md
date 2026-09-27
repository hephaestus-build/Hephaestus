# ADR 0045: Activity counts and lists work; it never scores or ranks people

**Status:** Accepted
**Date:** 2026-09-27
**Authors:** Felix T.J. Dietrich

## Context

Hephaestus shipped a gamification layer next to practice feedback: a weekly leaderboard ranking
members by a review score, Elo-style leagues with league points and a reset, experience points and
levels, and a weekly Slack digest of the leaderboard, each behind a workspace switch. Achievements
were already retired in v0.79.0.

The layer contradicted what the product says about feedback — advisory, about the work, never a grade
([practice feedback language](../contributor/practice-feedback-language.md)) — and the evidence on
measuring developers points the same way:

- **SPACE** (Forsgren, Storey, Maddila, Zimmermann, Houck and Butler,
  [*The SPACE of Developer Productivity*](https://queue.acm.org/detail.cfm?id=3454124), ACM Queue
  19(1), 2021) treats activity as one of five dimensions, beside satisfaction and well-being,
  performance, communication and collaboration, and efficiency and flow. Activity counts are useful
  context and misleading on their own: they miss the work that does not leave a trace, and they must
  not be read as a person's productivity.
- **DORA** ([DORA's software delivery metrics](https://dora.dev/guides/dora-metrics/)) warns against
  ignoring Goodhart's law by turning metrics into targets, and against using them to make teams
  compete; the point is a team improving against itself. A per-person ranking is the sharpest form of
  both.
- **GitHub removed contribution streaks** from profiles in May 2016
  ([*More contributions on your profile*](https://github.blog/news-insights/product-news/more-contributions-on-your-profile/)).
  Moldon, Strohmaier and Wachs studied that removal as a natural experiment
  ([*How Gamification Affects Software Developers*](https://arxiv.org/abs/2006.02371), ICSE 2021):
  once the counter was gone, long streaks and weekend activity dropped. The counter had been steering
  when and how people worked, not measuring it.

The data under the leaderboard is still worth showing. Developers ask what is waiting on them and what
they did; a team asks what happened this week. That needs counts and lists, not scores.

## Decision drivers

- Feedback earns trust or is not sent; a score beside it undermines both.
- A number a developer sees must be checkable: they can open what it counts.
- Activity is for the people in the workspace, never for a public read.
- A capability that is always right does not need a switch.

## Considered options

1. **Keep gamification behind its switches** and add an activity view beside it. Rejected: the
   switches default off, but the product still ships and documents a ranking, and every surface has
   to decide how the two relate.
2. **Keep the numbers, drop the ranking** — a sortable table of members by count. Rejected: a table
   sorted by count is a leaderboard with the medals removed.
3. **Counts and lists only, ordered by name, for the workspace only.** Chosen.

How the counts are drawn had its own options:

1. **A radar chart of the mix**, as GitHub's profile activity overview draws it. Rejected: it shows
   shares of a whole, so a quiet week and a busy one can have the same shape; the order of the axes
   changes the shape; and area exaggerates differences. Real profiles collapse to a spike on one
   axis.
2. **A calendar heat map**, as the contribution graph draws it. Rejected: colour carries the whole
   value, per-person quantiles make one shade mean different counts for different people, and it
   rewards activity on as many days as possible — the streak behaviour GitHub removed.
3. **Persisted dismissals or snoozes of review requests.** Rejected for now: a stored state per
   person and request needs its reverse, a way to go stale and a place in every scope. Deriving
   *covered* requests from the review state, below, settles most of the noise with nothing stored.

## Decision

Activity counts and lists work, for one developer (**Activity**) and for a workspace or team
(**Workspace activity**), and never scores, ranks or sorts people by what they did. It is always on.

- **Counts, no score.** Each kind of work is its own count over a time range, with no weights and no
  total.
- **One metric per chart, on its own scale.** Each kind of work is its own tile, with its headline's
  bars over the range from zero; kinds of different units never share an axis or a total. Only the
  kinds of one category share an axis, in that category's detail, and they stack only where they
  partition it: a review's verdicts and a comment's place add up to the category's total, while a
  pull request opened, merged and closed are steps of one lifecycle and stand side by side.
- **Members by name.** Workspace activity lists members alphabetically, never by a count, and the
  table has no count column to sort by. No bar, colour ramp or position compares one member with
  another: each member's cell is that member's own counts.
- **Grouped by the work.** The timeline has one row per pull request or issue, with how often each
  kind of activity happened on it, not one row per event. Its rows add up to the counts for the same
  range, and work the provider no longer has keeps its row without a link.
- **Covered review requests wait.** A review request is *covered* when its pull request is approved,
  or when a reviewer requested changes so the author acts first. It sits with the rest of what waits
  on someone else, apart from what needs the person, and is derived from the review state each time
  rather than dismissed and stored. So is a request the person already approved or requested
  changes on, which GitLab keeps listing until the author asks again; a comment-only review is not
  a verdict and leaves the request where it was.
- **Every count opens its list.** A count and its list follow the same rules.
- **Outcomes belong to the author.** A merge, or a close without merging, counts for the pull
  request's author and a closed issue for the issue's author; reviews of one's own pull request and
  bots are not counted.
- **For the workspace only.** Activity needs a role in the workspace and is never public. It reads the
  workspace's repositories and does not check each viewer's permissions on GitHub or GitLab.

## Consequences

- The schema change is expand/contract: this release stops reading and writing league points, XP and
  the leaderboard and league switches but leaves their columns in place, and a later release drops
  them.
- A workspace can no longer choose competition as a motivator. That is intended; an instance that
  wants a ranking builds it outside Hephaestus.
- Counts are not comparable with the old scores: nothing is weighted, merges count for the pull
  request's author, and a merge whose author is unknown counts for nobody. Three rules change
  quietly: a comment on one's own pull request counts as a comment; a review in an unknown or pending
  state is not counted; and the per-deployment list of bot logins excluded from self-review scoring
  is gone, since only human accounts are counted.
- A new kind of work is a new tile or chip with its own count and list, never a weight.
- A covered request can still be the one a person should answer — a team that expects every
  reviewer's approval, say. It is folded away, not hidden, and returns on its own once the approval
  or the request for changes no longer stands.

## Revisit trigger

Evidence from Hephaestus's own users that a count on Activity is being used as a target or a ranking
— for example, members asking to sort by it or reports of it being used in performance reviews — or
a request for a comparison between members that cannot be met without ordering people by a number.
