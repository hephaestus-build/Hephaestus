# ADR 0112: Practices across the workspace hides counts of fewer than 3 people

**Status:** Accepted
**Date:** 2026-11-18
**Amended:** 2026-11-23 by NP-04 (to be filed). The threshold is 5, and **Open feedback** has no typical range. ADR 0051 records the population change.
**Authors:** Hephaestus maintainers

**Amends:** [ADR 0051](0051-practices-across-the-workspace-counts-developers.md) (its choice of no smallest count), and replaces rule 23 of [ADR 0093](0093-members-see-activity-a-workspace-can-publish-its-public-work-and-private-practice-feedback-has-one-reader.md) ("No count is too small to show")

## Context

Practices across the workspace shows members counts of developers by standing.
A count of one or two developers, in a workspace of a small team, tells a member who the developers are.
ADR 0051 first used a smallest count of 3, then removed it on 2026-10-07 and accepted the residual risk.
ADR 0093 rule 23 kept that choice.
We now judge that risk too high for a page that every member reads, because the page also marks the reader's own part.

A split has four cells: Needs attention, Mixed feedback, Going well, and No standing yet.
They add up to the total.
If the page hides one small cell and shows the others and the total, the total minus the shown cells gives the hidden cell back.

## Decision drivers

- A member cannot learn the standing of one or two named developers from the page.
- The rule works in a workspace of 30 to 40 developers.
  There, ADR 0051 found that K = 5 and K = 10 hide almost everything.
- One home for the threshold, so that the server and the webapp cannot disagree.
- The rule shows what it hides, so that a reader does not take a hidden count for zero.

## Considered options

1. **No threshold, as today.** Rejected. A part of 1 or 2 developers identifies them to a member who knows the team. A split with all developers in one part tells every member everyone's standing.
2. **A threshold of 5.** Rejected. A split then needs at least 5 developers in each part that it shows. A workspace of 30 to 40 developers seldom has that, and the page shows almost nothing. This is the reason that ADR 0051 gave for rejecting K = 5.
3. **A threshold of 3 with complementary suppression.** Rejected.
   This hides the small cells, then the next-smallest, until the hidden cells hold 3.
   It closes the subtraction but not the inference.
   A reader who knows the rule knows that a hidden cell holds at least 1.
   The reader also knows that it holds at most the smallest shown cell.
   Some outputs fit only one split: (1,1,1,6) can only hide three cells of 1.
   Hundepool et al., *Statistical Disclosure Control* (2012) §4.3, assumes that the attacker knows the pattern.
4. **A threshold of 3, and a split shows all its cells or only its total.** Chosen.
   The PTAC names 3 as the minimum ([ADR 0051](0051-practices-across-the-workspace-counts-developers.md) source 6).
   Every output that hides cells fits several splits.
   In them, each hidden cell can hold several counts, one of them 3 or more.
5. **Noise or rounding.** Rejected.
   A rounded or noisy count can contradict the Practice profile of the reader.
   ADR 0051 requires that the page never contradicts it.
   A reader cannot tell a rounded count from a true count.
   Thus, the page would say less than it appears to.

## Decision

1. **A count of people of 1 or 2 is not shown.** It reads "fewer than 3". Zero is shown.
2. **A split shows all its cells or only its total.**
   A cell can hold 1 or 2 people, or all counted people can sit in one cell.
   Then every cell is hidden, the empty ones too, and only the total shows.
   The page does not say which of the two applies.
3. **The reader is not told why a split is hidden.** Naming the reason would tell members that someone is alone, or that everyone shares a standing.
4. **A total below 3 hides the total and every cell.**
5. **A typical range is read from the other developers only, and needs at least 3 of them.**
   Take a group of 3 that includes the reader.
   With the reader's own value beside its two quartiles, linear interpolation gives the other two values back.
   This covers the middle half of each tile and of open feedback.
   The count of developers with a standing in the tile follows the rule of decision 1.
6. **The threshold is 3 and has one home: `SmallCounts` on the server.** The webapp applies no threshold of its own. It shows what the server sends and marks a hidden count as hidden.
7. **Practice reviews, the admin view, has no small-count rule.** See the next section.

### Differencing

- The splits read the current standing and take no window. A reader cannot difference them across windows.
- The tiles take a window of 7, 30 or 90 days, or all time. Their counts can differ between windows. The difference shows how many developers joined or left the window, not the standing of anyone.
- The typical range is two quartiles of at least 3 other developers, each interpolated and rounded.
- A range whose two ends meet is withheld.
  It says that every other developer is within 1 of that value.
  A range of 0 to 0 says that each has 0.
- No range shown fixes the values of all the others.
  `WorkspaceSplitsTest` checks every set of 3 to 5 values up to 12.
  A range can still fix some of them (see Residual risks).

### The admin view is not changed

Practice reviews, the admin view, gets no small-count rule.
With **Per-person results for admins** off, an admin sees no names and no person filter.
Every result is still listed with the work item that it is about, and the author of that work is public on GitHub or GitLab.
Counts hidden beside such a list would protect nobody.
The workspace owner still decides if admins see results with a name.

So no text says or implies that admins cannot see your results, or that results are anonymous.
Text says what is true.
With the setting off, admins see no per-person view, no names and no person filter.
Each result stays attached to the work that it is about.

## Consequences

- A small workspace sees "fewer than 3" and total-only bars often. The page says less, and it says that it hides.
- One developer alone in a standing hides the split of that practice for everyone.
- The wire contract changes: `developers` and `noneYet` on a split, the count in a cell and the tile's count become nullable. This is not an additive change in the sense of [ADR 0103](0103-the-public-api-grows-only-by-addition-and-every-client-tolerates-what-it-does-not-know.md), which these member endpoints are not part of. The generated clients carry it.
- A total-only bar has no **You** bracket. The reader's own standing stays on their Practice profile.

## Residual risks

- A group's standing follows from its practices by a rule, not a sum.
  A member who knows the rule can read the split of a practice.
  The member can then sometimes infer something about the split of its group, and the other way round.
- A tile's counts differ between windows. A member can read from this how many developers joined or left the window. This is not their standing.
- A member who knows a developer can still guess that developer's standing from a part of 3 or more. The threshold gives a minimum group, not secrecy.
- Every count includes the reader when the reader is counted.
  A part of 3 that holds the reader tells the reader that 2 others share their standing.
  It does not tell who they are.
- Figures start at 0. A range whose low end is 0 tells a member that the lower others hold exactly 0.
  With 3 others, a range of 0 to 2 means two of them hold 0.
  No name is attached, but in a small team this can narrow who it is.
- The typical ranges of several windows share developers.
  A member who reads all of them gets more equations over the same values, and can narrow them.
  Rounding and the others' changing values blur that.

## Open question

With **Per-person results for admins** off, admins still see each result with its work item.
Whether to hide work items from admins is a separate decision.
It changes what review, triage and audit can do, so it needs its own ADR.

## Confirmation

- `SmallCountsTest`: a count of 1 or 2 is hidden.
  A split with such a cell, or with everyone in one cell, shows only its total.
  A total below 3 hides everything.
  `#shouldLeaveEveryHiddenCellOpenToAReaderWhoKnowsTheRule` groups every split of up to 12 people per cell by its output.
  It checks that each hidden cell could hold at least two counts, one of them 3 or more.
- `WorkspaceSplitsTest` and `PracticesAcrossWorkspaceServiceTest`: a typical range needs 3 developers besides the reader, and a range whose ends meet is withheld. `WorkspaceSplitsTest#shouldLeaveNoRangeThatFixesEveryOtherValue` groups every set of 3 to 5 values from 0 to 12 by the range shown.
- Review catches a threshold in the webapp. `PracticeProfilePageHeader.tsx` and the page read only what the server sends.

## Revisit trigger

A member reports that the page told them the standing of a named developer.
A workspace of fewer than 10 developers that finds the page empty.
A decision to hide work items from admins.

## Sources

- [UK Data Service, Handbook on Statistical Disclosure Control for Outputs](https://ukdataservice.ac.uk/app/uploads/sdc-handbook-v2.0.pdf): secondary suppression.
  It hides a second cell where a total would give one back.
- *Statistical Disclosure Control*, Hundepool and others, Wiley 2012.
  Its section 4.3 requires that a suppression pattern holds against an attacker who knows it.
- [US Department of Education PTAC, Frequently Asked Questions: Disclosure Avoidance](https://studentprivacy.ed.gov/sites/default/files/resource_document/file/FAQs_disclosure_avoidance_0.pdf): 3 is the absolute minimum cell size. We use the minimum because larger values hide most splits in small workspaces.

## Already priced and declined

- A smallest count of 5 or 10 ([ADR 0051](0051-practices-across-the-workspace-counts-developers.md)).
  It hides almost every split in a workspace of 30 to 40 developers.
- No smallest count ([ADR 0051](0051-practices-across-the-workspace-counts-developers.md), amendment of 2026-10-07): the residual risk is now too high.
