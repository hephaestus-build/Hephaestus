# ADR 0051: Practices across the workspace counts developers and never names one

**Status:** Proposed
**Date:** 2026-10-02
**Amended:** 2026-10-07. The page has no smallest count. *Amendment of 2026-10-07* gives the change.
**Authors:** Valentin Grüner
**Builds on:** [ADR 0045](0045-activity-counts-work-and-never-ranks-people.md) (no score, no rank),
[ADR 0047](0047-the-practice-profile-has-one-reader.md) (the Practice profile has one reader)

## Context

A developer who reads their own Practice profile cannot tell if a practice group is hard for the whole workspace.
Research on dashboards for learners supports a comparison only under conditions [1–3].
The subject is the practice group, not the reader.
The reference group has a name.
The comparison is optional and is never the first thing that the reader sees.
A dashboard that compares learners can also cause competition [1].

ADR 0047 closes the Practice profile to every reader but the developer.
It also says that a cohort view is a new audience that needs its own ADR.
ADR 0045 rules out scores, ranks, and leaderboards.
This ADR records that audience.

## Decision drivers

- No developer is named, ranked, or put in an order of people.
- The comparison is never the first thing that the reader sees. The reader opens it.
- The page never contradicts the reader's own Practice profile.
- Every reader sees the same counts.

## Considered options

Where the comparison is:

1. **A layer in the tables of the Practice profile.** Rejected. It puts the comparison in the record of the developer, which ADR 0047 protects.
2. **A tab on the Practice profile.** Rejected for the same reason.
3. **A separate page in the sidebar, with links to the Practice profile.** Chosen.

What the page shows of the reader:

1. **A standing badge and a trend on each group, with bars for the selected window.** Rejected. The bars and the profile then read different spans and can disagree.
2. **Only the You marker and the values of the reader on the tiles.** Chosen.

How small a shown count can be:

1. **K = 5, with a merged bar when a split cannot show all parts.** Rejected. A practice split then needs 6 developers in each part. Workspaces of 30 to 40 developers seldom have that. The merged bar also shows no standing.
2. **K = 10, the default of the ONS and the NCHS [4, 6].** Rejected. Almost every split in a workspace of 30 to 40 developers is then held back.
3. **K = 3, with all four parts or only the total.** Chosen on 2026-10-02. Replaced on 2026-10-07.
4. **No smallest count. Every split shows all its parts.** Chosen on 2026-10-07. The section *Amendment of 2026-10-07* gives the reasons.

How the reader controls the comparison:

1. **A switch that hides the comparison.** Rejected. To open the page is already the choice of the reader. The switch also adds a state to each part of the page.
2. **A question about the estimate of the reader before each split.** Rejected for now. A user test must show that it helps.

## Decision

**Practices across the workspace** is a separate page that shows the workspace, not the reader.
It counts developers in each practice group and practice and never names one.
`WorkspaceSplits` counts the splits and the middle halves.

### What the page counts

- A *developer with a standing* has a standing in one or more practice groups on the page.
- Hidden members are not counted, as in every workspace total.
- Each bar counts each developer at the current standing that their Practice profile shows. The bars have no window.
- Three tiles use a window of 30 days, 90 days, or all time. The open feedback tile counts open feedback now.
- A group lists the practices that review is admitted for, in catalog order. This list is the same for every reader.

### What the page can show

The page shows every count, however small.
The same rules apply whether the reader is in the count or not, so every reader sees the same bars.

- A split shows its four parts, *none yet* included, also a part with 1 developer or with none.
- A split that counts nobody shows that no developer has a standing yet.
- A tile shows the value of the reader and the middle half of the developers counted. The reader is in it when counted.
- The window tiles count only developers with a standing in the window.
- Open feedback counts eligible developers who are not hidden members.
- The middle half shows when 1 developer or more is counted. This rule includes the open feedback tile.
- The tiles show the number of developers with a standing in the window, also when it is small.

The amendment changes no rule about who and what is counted.
The AI choice of each developer, hidden members, hidden repositories, and invalidated observations apply as before.

### What the page shows of the reader

- The **You** marker, only on a split that counts the reader. The values of the reader on the tiles. A group shows no badge or trend.
- A group and each practice link to the same item in the Practice profile with **Open in your Practice profile**.
- Workspace admins read nothing new. Instance administrators can read the page through **View as user**.

### Amendment of 2026-10-07

The first version used K = 3 (`CohortPrivacyPolicy`).
Every count held 4 developers or more.
A split with a smaller part showed only its total, and a smaller total showed nothing.
Three differences between splits were also guarded.

The maintainer removed these thresholds on 2026-10-07.
The page now shows all counts and all splits.
The supervisor is to be informed.

The reasons:

- The workspace is closed. Its members already see the pull requests, issues, and reviews of each other on the provider.
- The page counts standings in practices. It shows no health, income, or other sensitive attribute.
- With K = 3, most bars in a real workspace stayed held back until many developers had a review. The page then showed almost nothing.

The page accepts a larger risk than before.
A part can hold 1 developer.
A reader who knows the standings of the other developers in a split can find the standing of the last one.
The middle half can be equal to the value of one developer.
The page and the user docs say this to the reader.

### The middle half

Only the 25th and the 75th percentile leave the server. They are interpolated linearly and rounded.
The page never shows a minimum, a maximum, an average, or a count at one value.
With few developers, a quartile can be equal to the value of one developer [4].
The page never says whose value it is.

## Consequences

- ADR 0047 stays valid. The Practice profile has one reader, and this page shows other developers only in a count.
- Since 2026-10-07, small groups show the same bars as large ones.
- In a small workspace, a reader can sometimes tell the standing of another developer from a count.
- The local practices demo shows small and large splits with synthetic members.

## Known limitation

The page has no smallest count, so it does not guard a count, a difference between counts, or two reads against each other [8].
A reader can compare two windows, or the page before and after the review of a colleague.

## Open decisions

- **Source use.** The page reads observations of other developers under `PRACTICE_FEEDBACK_DELIVERY`. The maintainer and the controller decide if a count needs its own purpose. See `docs/admin/dsms/artifact-source-governance.md`.

## Revisit trigger

A workspace that asks to show the same counts to its instructors.
A request to compare with a selected peer group.
A user test that shows that the page lowers self-efficacy for readers at Needs attention.

## Sources

1. Jivet et al. 2017, *Awareness is not enough*: <https://link.springer.com/chapter/10.1007/978-3-319-66610-5_7>
2. Jivet et al. 2018, *License to evaluate*: <https://doi.org/10.1145/3170358.3170421>
3. Teasley 2017, *Student facing dashboards: one size fits all?*: <https://link.springer.com/article/10.1007/s10758-017-9314-3>
4. UK Data Service, *Handbook on Statistical Disclosure Control for Outputs* v2.0: <https://ukdataservice.ac.uk/app/uploads/sdc-handbook-v2.0.pdf>
5. NCHS, *Data Presentation Standards for Proportions*: <https://www.cdc.gov/nchs/data/series/sr_02/sr02_175.pdf>
6. ABS DataLab, *Safe Outputs*: <https://www.abs.gov.au/system/files/documents/bec1cc42a3e20dd01d9748b621f8b8b7/DataLab%20Safe%20Researcher%20Virtual%20Training_Pt3_Safe%20Outputs_JAN%202024.pdf>
7. US Department of Education PTAC, *Frequently Asked Questions: Disclosure Avoidance*: <https://studentprivacy.ed.gov/sites/default/files/resource_document/file/FAQs_disclosure_avoidance_0.pdf>
8. ONS, *Policy on protecting confidentiality in tables of birth and death statistics*: <https://www.ons.gov.uk/methodology/methodologytopicsandstatisticalconcepts/disclosurecontrol/policyonprotectingconfidentialityintablesofbirthanddeathstatistics>
