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

## Decision

Activity counts and lists work, for one developer (**Activity**) and for a workspace or team
(**Workspace activity**), and never scores, ranks or sorts people by what they did. It is always on.

- **Counts, no score.** Each kind of work is its own count over a time range, with no weights and no
  total.
- **Members by name.** Workspace activity lists members alphabetically, never by a count.
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
- A new kind of work is a new row with its own count and list, never a weight.

## Revisit trigger

Evidence from Hephaestus's own users that a count on Activity is being used as a target or a ranking
— for example, members asking to sort by it or reports of it being used in performance reviews — or
a request for a comparison between members that cannot be met without ordering people by a number.
