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
- The comparison is the reader's choice and never their first impression.
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
- `CohortPrivacyPolicy` is the only place that decides what may be shown: a count appears only when it
  holds at least five developers other than the reader. A three way split that fails collapses to *has
  a standing* against *none yet*; when that fails too, the group shows no split. Collapse before omit,
  omit rather than show a small number. Each window (term, 30 days, 90 days) is checked on its own.
- The tiles show the reader's own figure and the middle half of the observed developers, never a
  minimum, maximum, average or count at one value. There is no tile for feedback.
- Design C: each group first asks where the reader thinks they stand. The estimate stays in the
  reader's browser, is never sent to the server and enters no workspace figure.
- Workspace admins read nothing new. Instance administrators read the page through **View as user**,
  as they read every other practice page.

## Consequences

- ADR 0047 stands: the Practice profile still has one reader, and this page shows other developers only
  inside a count.
- Moving the window between term, 30 and 90 days lets a reader subtract two windows; each window is
  gated on its own, but a determined reader in a small workspace may still learn about a bucket. The gate
  is the mitigation, not a proof.
- Synthetic members seeded locally show the page; in a real workspace it stays empty until five other
  developers are reviewed.

## Open decisions

- **What a term is.** Until a workspace can set its term dates, *Term* reads the standing's 90 day
  look-back, the same evidence as *90 days*.
- **Zero counts.** A bucket holding no other developer is treated as too small, which collapses more
  splits than strictly needed.
- **When the page asks again**, per term or per changed standing, and whether to measure design C
  against a page that does not ask.
- **The link to Heph** after every group is answered is left out until Heph can talk about estimates
  that never leave the browser.

## Revisit trigger

A workspace that asks for its instructors to see the same counts, a request to compare against a
chosen peer group, or a user test showing the page lowers self efficacy for readers at Needs attention.
