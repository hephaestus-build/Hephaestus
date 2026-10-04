# ADR 0051: Practices across the workspace counts developers and never names one

**Status:** Proposed
**Date:** 2026-10-02
**Authors:** Valentin Grüner
**Builds on:** [ADR 0045](0045-activity-counts-work-and-never-ranks-people.md) (no score, no rank),
[ADR 0047](0047-the-practice-profile-has-one-reader.md) (the Practice profile has one reader)

## Context

A developer who reads their own Practice profile cannot tell whether a practice group that needs their
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
- The comparison is never the first thing seen. The reader opens it.
- The page never contradicts the reader's own Practice profile.
- One class decides what may be shown.

## Considered options

Where the comparison lives:

1. **A layer in the Practice profile's tables.** Rejected: it makes the comparison part of the
   developer's own record, the attribute of a person ADR 0047 protects, and the first thing seen.
2. **A tab on the Practice profile.** Rejected for the same reason, with worse discoverability.
3. **Its own page, reached from the sidebar, linking to the reader's own group and practices in the
   Practice profile.** Chosen.

What the page shows of the reader:

1. **The reader's standing badge and trend on each group, bars read over the chosen window, and the
   reader's own group and practice opened over the page.** Rejected: the bars and the profile read
   different spans, so the marker and the profile could disagree, and the page becomes a second profile.
2. **Only the You marker and the reader's own tile values, with links to the Practice profile.** Chosen.

How small a shown count may be:

1. **K = 5, and a split that cannot show all three standings collapses to *has a standing* against
   *none yet*.** Rejected: a part needs six developers, so a workspace of thirty to forty rarely shows
   a full split for a practice. The collapsed bar is also hard to read: its merged part names no standing.
2. **K = 3, and a split shows all four parts or only its total.** Chosen. Each part stands for three
   others rather than five, so one piece of outside knowledge narrows a part down sooner. Every count
   shown, and every count a reader can subtract, still holds at least K.

How the reader controls the comparison:

1. **A switch that turns the comparison off, remembered in the browser.** Rejected: opening the page is
   already the reader's choice, and the switch adds a state to every surface of the page.
2. **Ask for the reader's estimate before each split is shown.** Not taken. Whether reflecting first
   helps is left for a user test.

## Decision

**Practices across the workspace** is a page of its own and a view of the workspace, not the reader's
profile. It counts developers in each practice group and practice and never names one.
`CohortPrivacyPolicy` is the only place that decides what it may show.

### What the page counts

- A *developer with a standing* is an eligible member with a standing in at least one practice group
  that the page shows. Observations that did not apply or stayed undetermined give no standing. Hidden
  members are left out, as they are of every workspace total.
- Every bar counts each developer by their current standing: the standing their Practice profile shows
  now, read by the same service over the same look-back. The bars take no window. So the **You** marker
  is always on the part that the reader's profile names.
- The reviewed work, going well and needing attention tiles take a window: the last 30 days, the last
  90 days, or all time. The page opens on the last 30 days. Each window is checked on its own. The open
  feedback tile counts what each eligible developer's Practice profile shows open now, and takes no window.
- A group lists the practices that review is admitted for, in catalog order, the same for every reader.
  A practice switched off is not listed.

### What the page may show

K, the fewest developers other than the reader that a shown count may stand for, is 3.

- A split shows Needs attention, Mixed feedback, Going well and *none yet* only when each of the four
  holds at least K + 1 developers, counted over every developer with a standing. So whoever reads it,
  each part stands for at least K others, and every reader sees the same shape. Otherwise the whole
  split is held back, never one part: the page states the total, and a missing part would be the total
  less the rest. A part with no developer is too small too.
- A split held back still shows its total while the total holds at least K + 1 developers. The total of
  every split is every developer with a standing, so it says nothing the page total does not. Below that,
  the split shows nothing.
- The total of developers with a standing shows only while it holds K others. The bars' total and the
  tiles' total in each window are checked on their own.
- A group's developers with a standing are everyone with a standing in any of its practices. So a reader
  can subtract a practice's split from its group's, or add up the practices' splits. Each cell a reader
  can work out this way must hold none or at least K developers. A practice whose developers with a
  standing fall short of its group's by 1 to K − 1 is held back. Every practice of a group is held back
  when the practices shown add up to 1 to K − 1 more developers with a standing than the group has.
- A tile shows the reader's own value and the middle half of the developers it counts: the 25th to the
  75th percentile, interpolated and rounded. It shows the middle half only from 2K others, so neither
  outer quarter can be one developer's value. It never shows a minimum, a maximum, an average, or a count
  at one value.

### What the page shows of the reader

- The **You** marker on their part of a bar and their own value on each tile. A group shows no standing
  badge, trend or window of the reader.
- The group panel links to the same group in the Practice profile, and each practice row links to the
  same practice there. Both links say **Open in your Practice profile**.
- Workspace admins read nothing new. Instance administrators read the page through **View as user**, as
  they read every other practice page.

## Consequences

- ADR 0047 stands: the Practice profile still has one reader, and this page shows other developers only
  inside a count.
- A practice is held back more often than its group.
- In a real workspace, most bars stay held back until enough developers are reviewed for parts of four.
  The local practices demo seeds synthetic members that show every state.
- **Each read is guarded on its own. Two reads are not guarded against each other.** This falls short of
  the second driver.
  - *Windows.* The bars take no window, so no two windows of a split exist to subtract. The tiles' middle
    halves still change with the window: a developer reviewed only between day 31 and day 90 is in the
    90-day tiles and not in the 30-day tiles. Two windows read together can narrow one developer down.
  - *Time.* The figures are live. A reader who loads the page before and after a colleague's review,
    which the team can see on the provider, can read the colleague's new part off the change, or a new
    tile value off a moved quartile.
  - Mitigations not taken, since each changes the page: one window for the tiles, figures frozen at the
    start of a day or week, and counts rounded to multiples of K.
- Not decided here:
  - **Which source use the counts read under.** The scan authorizes other developers' observations under
    `PRACTICE_FEEDBACK_DELIVERY`, the purpose that delivers feedback to the developer it is about. Each
    observation leaves only inside a count. Whether an aggregate shown to another developer needs a purpose
    and source use of its own is for the maintainer and the controller to decide in
    `docs/admin/dsms/artifact-source-governance.md`.
  - **Zero counts.** A part with no other developer counts as too small, which holds back more splits
    than strictly necessary.

## Revisit trigger

A workspace that asks for its instructors to see the same counts, a request to compare against a
chosen peer group, or a user test that shows that the page lowers self-efficacy for readers at Needs
attention.
