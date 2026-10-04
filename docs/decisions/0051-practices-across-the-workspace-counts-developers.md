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
3. **Its own page, reached from the sidebar, linking to the reader's own group and practices in the
   Practice profile.** Chosen.

## Decision

**Practices across the workspace** is a page of its own and a view of the workspace, not the
reader's profile. It shows how the workspace's developers with a standing split across Needs
attention, Mixed feedback and Going well in each practice group, counted in developers. The reader
appears only as the **You** marker on their part of a bar and as their own value on the tiles. A developer with a
standing is an eligible developer with a standing in at least one practice group the page shows;
whoever only has observations that did not apply or stayed undetermined is not one of them.

- The figures come from guarded scans of the workspace's observations, classified by the same
  standing and group standing rules the Practice profile uses. Hidden members are left out, as they are
  of every workspace total.
- `CohortPrivacyPolicy` is the only place that decides what may be shown, and every reader of a
  workspace sees the same shape. K, the fewest other developers a shown count may stand for, is
  three. A split shows Needs attention, Mixed feedback, Going well and *none yet* only when each of
  the four holds at least four developers, counted over every developer with a standing, so whoever
  reads it each part stands for at least three others; otherwise the whole split is held back, never
  a part of it, since the page states how many developers have a standing and a missing part would
  be that total less the rest. Omit rather than show a small number.
- **A split held back still shows its total.** The server sends the shape: the split, the total
  only, or nothing. The total of a split is every developer with a standing, the same for every
  split, so it says nothing the page total does not. It shows while it holds at least four
  developers, the rule for a part; below that the split is held back whole. The bar is then one
  neutral bar with the total and **Split held back** under it, so the row says that the group
  counts developers but not how they split.
- **The bars count the current standing.** Every split, for a group and for a practice, counts each
  developer by the standing their Practice profile shows now: the same service, read over the same
  look-back and trend horizon (90 days today), whatever window the tiles show. So the **You** marker
  is always on the part the reader's profile names, and a bar never sets one span beside another.
- **The window applies to the tiles alone.** Its toggle sits in the tiles' heading row. Each window
  (the last 30 days, the last 90 days, all time) is checked on its own. *All time* reads every
  observation, with no lower bound, and reads every standing over all of it by the same rule as the
  shorter windows. The page opens on the last 30 days. The tiles are a read of their own: the bars
  and the open feedback come in a read that takes no window, so a new window reads them no second time.
- The total of developers with a standing shows only while it holds three others: the bars' total
  of developers with a current standing, and the tiles' total in the window, each on its own.
- A group's practices are the ones review is admitted for, in catalog order, the same list for every
  reader whatever their own evidence holds; a practice switched off is not listed. Each is split by the
  same rule over the same developers with a standing. Because a group's developers with a standing are
  everyone with a standing in any of its practices, the cells a reader can work out by subtraction
  must each hold none or at least three: a practice whose developers with a standing fall short of
  its group's by one or two is withheld, and every practice of a group is withheld when the practices
  shown add up to one or two more developers with a standing than the group has. A practice therefore
  is withheld more often than its group.
- The reviewed work, going well and needing attention tiles show the reader's own figure and the
  middle half of the developers with a standing, the 25th to the 75th percentile interpolated and
  rounded, never a minimum, maximum, average or count at one value, and only from six others, twice
  K. The open feedback tile counts per developer what their Practice profile shows open now, by the
  profile's own rule, for the reader and for every eligible developer alike whatever the window, so
  it never sets one moment beside a span; read in one pass.
- The page asks for no estimate first. Every group shows at once. A group opens over the page with
  its bar and its practices' bars, and nothing about the reader beyond the marker: no standing
  badge, no trend, no window. A bar that shows only its total, and a held back bar with its dashed
  track and reason, say nothing of the reader.
- **The reader's own learning stays in the Practice profile.** The group's panel links to the same
  group in the profile, and each practice row links to the same practice there. Both links say
  **Open in your Practice profile**. The page reads none of the profile's own data.
- Workspace admins read nothing new. Instance administrators read the page through **View as user**,
  as they read every other practice page.

## Consequences

- ADR 0047 stands: the Practice profile still has one reader, and this page shows other developers only
  inside a count.
- The counts are guarded within one read, not between reads. See *Known limitation* below.
- Synthetic members seeded locally show the page; in a real workspace it stays empty until enough
  developers are reviewed for a part of four.
- The page always shows the workspace. An earlier version let the reader switch the comparison off
  and remembered the choice in the browser; the maintainer dropped that switch, since opening the
  page is already the reader's choice and the switch added a state to every surface of it.
- The page is not the reader's profile. An earlier version showed the reader's standing badge and
  trend on each group, read the bars over the chosen window, and opened the reader's own group and
  practice over the page. The two standings could then disagree. The maintainer decided on
  2026-10-04 that the page is an aggregate view: the bars count the current standing, the window
  moves only the tiles, and the reader's own levels are reached by links to the Practice profile.

## Lowering K and dropping the collapse

K was five, and a split that could not show all three standings collapsed to *has a standing*
against *none yet*. The maintainer lowered K to three and dropped the collapsed shape.

- **Why.** At five, a part needed six developers, so a course of thirty to forty rarely showed a
  full split for a practice and readers met mostly collapsed or held back bars. The collapsed bar was
  also not understood: its merged part could not say a standing.
- **What it costs.** Each shown part now stands for three others rather than five, so a reader who
  knows two of them learns less about the rest, but one more piece of outside knowledge narrows a
  part down sooner. Three is still at least K for every count shown and every count a reader can
  subtract, and the guards above are unchanged in kind.
- **What changes.** A split shows all four parts or nothing, so there is one fewer shape to explain
  and nothing for the guards to treat as a second kind of part.

## Known limitation

Each read is safe on its own; two reads are not guarded against each other, so this falls short of
the driver that nothing another reader sees can be traced back to one developer.

- **Windows.** The bars take no window, so no two windows of a split exist to subtract. The tiles'
  middle halves still change with the window: a developer reviewed only between day 31 and day 90 is
  in the 90 day tiles and not the 30 day ones. A middle half shows only from six others and never a
  value of one developer, but two windows read together can still narrow one down.
- **Time.** The figures are live. A reader who loads the page before and after a colleague's review,
  which the whole team can see on the provider, can read the colleague's new bucket off the change,
  or a new tile value off a moved quartile.

Mitigations considered and not yet taken, since each changes the page: offering one window only for
the tiles, freezing the figures at the start of a day or week, and rounding counts to multiples of K.
Counting the bars by the current standing, which removed the window risk for the bars, was taken.

## Open decisions

- **Which source use the counts read under.** The scan authorizes other developers' observations
  under `PRACTICE_FEEDBACK_DELIVERY`, the purpose that delivers feedback to the developer it is about,
  and each observation leaves only inside a count. Whether an aggregate shown to another developer
  needs a purpose and source use decisions of its own is for the maintainer and the controller to
  decide in `docs/admin/dsms/artifact-source-governance.md`.
- **Zero counts.** A bucket holding no other developer is treated as too small, which holds back
  more splits than strictly needed.
- **Whether to ask first.** A version that asked for the reader's estimate before showing each split
  was built and set aside; whether reflecting first helps is left for a user test.

## Revisit trigger

A workspace that asks for its instructors to see the same counts, a request to compare against a
chosen peer group, or a user test showing the page lowers self efficacy for readers at Needs attention.
