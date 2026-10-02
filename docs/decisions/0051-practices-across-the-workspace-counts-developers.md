# ADR 0051: Practices across the workspace counts developers and never names one

**Status:** Proposed
**Date:** 2026-10-02
**Authors:** Valentin Grüner
**Builds on:** [ADR 0045](0045-activity-counts-work-and-never-ranks-people.md) (no score, no rank),
[ADR 0047](0047-the-practice-profile-has-one-reader.md) (the Practice profile has one reader)

## Context

A developer reading their own Practice profile cannot tell whether a practice group that needs their
attention is hard for everyone in the workspace or within reach of most. Research on social comparison
in learning dashboards supports showing that, but only under conditions: the practice group, not the
reader, is the subject; the reference group is named; reaching a standing is said to be within reach;
a sentence keeps those already doing well from drifting to the middle; and the comparison is optional,
never the first thing seen.

ADR 0047 closes the Practice profile to every reader but the developer and says a cohort view is a new
audience decided in its own ADR. ADR 0045 rules out scores, ranks and leaderboards. This ADR records
that audience.

## Decision drivers

- No developer is named, ranked or placed in an order of people.
- Nothing another reader sees can be traced back to one developer.
- The comparison is never the first thing seen: it is a page of its own, which the reader opens.
- The rule that decides what may be shown has one home.

## Considered options

1. **A layer in the Practice profile's tables.** Rejected: it makes the comparison part of the
   developer's own record, the attribute of a person ADR 0047 protects, and the first thing seen.
2. **A tab on the Practice profile.** Rejected for the same reason, with worse discoverability.
3. **Its own page, reached from the sidebar, each row linking back to the reader's own group.** Chosen.

## Decision

**Practices across the workspace** is a page of its own. It shows the reader their own standing in
each practice group as a word, and beside it how the workspace's observed developers split across Needs
attention, Mixed feedback and Going well, counted in developers.

- The figures come from one guarded scan of the workspace's observations, classified by the same
  standing and group standing rules the Practice profile uses. Hidden members are left out, as they are
  of every workspace total.
- `CohortPrivacyPolicy` is the only place that decides what may be shown, and every reader of a
  workspace sees the same shape: a part of a split appears only when it holds at least six
  developers, counted over every observed developer, so whoever reads it, it stands for at least
  five others. *Has a standing* against *none yet* is checked first and for every split, since the
  page states how many developers were observed and a split therefore states *none yet* as the rest;
  a full split shows it as a fourth part. When it fails, the group shows no split. When it holds, a
  three way split with a part under six collapses to those two. Collapse before omit, omit rather
  than show a small number. Each window (the last 30 days, the last 90 days, all time) is checked on
  its own. *All time* reads every observation, with no lower bound. The page opens on the last 30
  days.
- The observed total shows only while it holds five others, and the eligible total beside it only
  while the others it adds are none or five, since their difference is a count of its own.
- A group's practices are split by the same rule over the same observed developers. Because a
  group's *has a standing* is everyone with a standing in any of its practices, the cells a reader
  can work out by subtraction must each hold none or five: a practice whose *has a standing* falls
  short of its group's by one to four is withheld, and every practice of a group is withheld when
  the practices shown add up to one to four more developers with a standing than the group has. A
  practice therefore is withheld more often than its group.
- The tiles show the reader's own figure and the middle half of the observed developers, the 25th
  to the 75th percentile interpolated and rounded, never a minimum, maximum, average or count at one
  value, and only from ten others. The open feedback tile counts per developer what their Practice
  profile shows open now, by the profile's own rule, for the reader and for every eligible developer
  alike whatever the window, so it never sets one moment beside a span; read in one pass.
- The page asks for no estimate first. Every group shows at once. A group opens over the page with
  its practices, a practice opens over its group with the reader's own observations and feedback,
  and *Open the group* leads on to the reader's own Practice profile.
- Workspace admins read nothing new. Instance administrators read the page through **View as user**,
  as they read every other practice page.

## Consequences

- ADR 0047 stands: the Practice profile still has one reader, and this page shows other developers only
  inside a count.
- The counts are guarded within one read, not between reads. See *Known limitation* below.
- Synthetic members seeded locally show the page; in a real workspace it stays empty until enough
  developers are reviewed for a part of six.
- The page always shows the workspace. An earlier version let the reader switch the comparison off
  and remembered the choice in the browser; the maintainer dropped that switch, since opening the
  page is already the reader's choice and the switch added a state to every surface of it.

## Known limitation

Each read is safe on its own; two reads are not guarded against each other, so this falls short of
the driver that nothing another reader sees can be traced back to one developer.

- **Windows.** A developer reviewed only between day 31 and day 90 is in the 90 day counts and not
  the 30 day ones. When few others differ between the two, subtracting one window's split from the
  other's shows that developer's bucket.
- **Time.** The figures are live. A reader who loads the page before and after a colleague's review,
  which the whole team can see on the provider, can read the colleague's new bucket off the change,
  or a new tile value off a moved quartile.

Mitigations considered and not yet taken, since each changes the page: offering one window only,
holding a split back when the developers between two windows are one to four, freezing the figures
at the start of a day or week, and rounding counts to multiples of five.

## Open decisions

- **Which source use the counts read under.** The scan authorizes other developers' observations
  under `PRACTICE_FEEDBACK_DELIVERY`, the purpose that delivers feedback to the developer it is about,
  and each observation leaves only inside a count. Whether an aggregate shown to another developer
  needs a purpose and source use decisions of its own is for the maintainer and the controller to
  decide in `docs/admin/dsms/artifact-source-governance.md`.
- **Zero counts.** A bucket holding no other developer is treated as too small, which collapses more
  splits than strictly needed.
- **Whether to ask first.** A version that asked for the reader's estimate before showing each split
  was built and set aside; whether reflecting first helps is left for a user test.

## Revisit trigger

A workspace that asks for its instructors to see the same counts, a request to compare against a
chosen peer group, or a user test showing the page lowers self efficacy for readers at Needs attention.
