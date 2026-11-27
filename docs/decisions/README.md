# Architecture Decision Records

An architecture decision record (ADR) records one decision that shapes Hephaestus, the options that we rejected, and the reasons.
Each ADR is one file in this directory. Its file name starts with its number.
The rules below say how to write one and how it changes over time.

## These are repo-only, not on the docs site

`docs/decisions/`, `docs/auth-architecture.md` and `docs/auth-glossary.md` are outside the three guides that the docs site reads.
They never appear on [the site](https://docs.hephaestus.build/).
This is a decision.
They use internal class names, the pull request that changes the code also changes them, and their reader has the repository open.

- **Link an ADR by absolute GitHub URL from a published page.** Use `https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/<file>.md`. A relative `../decisions/…` link from `docs/user/`, `docs/contributor/` or `docs/admin/` resolves to nothing on the site, and `onBrokenMarkdownLinks: 'throw'` fails the docs build. Relative links between files in this directory are correct.
- **An operator fact does not live only here.** If a self-hoster must do a step, the Admin Guide (`docs/admin/`) owns it, and the ADR links to it.

## How we write ADRs

The rules come from [Nygard](https://www.cognitect.com/blog/2011/11/15/documenting-architecture-decisions), [MADR 4](https://github.com/adr/madr/blob/4.0.0/template/adr-template.md), Olaf Zimmermann's guidance on [creating](https://ozimmer.ch/practices/2023/04/03/ADRCreation.html) and [reviewing](https://ozimmer.ch/practices/2023/04/05/ADRReview.html) ADRs, and the [AWS ADR process](https://docs.aws.amazon.com/prescriptive-guidance/latest/architectural-decision-records/adr-process.html).
Copy [the template](0000-template.md) and use the next free number.

### Shape

- **One decision per ADR.** The title states the decision as a sentence.
- **Context** gives the forces and the real constraint in a few sentences. Do not tell the story of an incident or list run numbers.
- **Considered options** give the real cost of each option. Never add an option that cannot work only to make the chosen one look good. Rejected options stay forever, so that nobody proposes them again without a new reason.
- **Decision** is a set of rules that a reviewer can check against a diff. Detailed design goes to a contributor page, and the ADR links it.
- **Consequences** include the bad ones.
- **Confirmation** names the test, ArchUnit rule or CI gate that fails when the code breaks the decision. If nothing checks it, write `Not enforced.` and say what review catches it. MADR 4 calls this section Confirmation.
- **Revisit trigger** names a signal that we can observe.
- **Sources** name one to three trusted sources and what each one changes in the decision: a standard or RFC, the official docs of the tool, a peer-reviewed paper, or the docs of a well-run open-source project. Read the source before you cite it. If we differ from common practice on purpose, say why in one sentence.
- **Length:** one or two pages. A longer ADR usually holds design that belongs on a contributor page.

### Status

The status line holds one value:

| Status | Meaning |
| --- | --- |
| `Proposed` | The decision is under review in an open pull request. |
| `Accepted` | The decision is in force. |
| `Superseded by NNNN` | A later ADR replaces this one. Read that ADR. |
| `Deprecated` | The decision no longer applies, and no ADR replaces it. The last update gives the reason. |

A rejected proposal is a closed pull request, not a status.
"Superseded in part" is not a status. A partial change is an `**Amended by:**` link in the header.
The header holds `**Status:**`, `**Date:**` and `**Authors:**`, then optional relation lines with links: `**Supersedes:**`, `**Amends:**`, `**Amended by:**`, `**Builds on:**` and `**Relates to:**`.

### Correct, update or supersede

An ADR records a decision. It is not a changelog. A bug fix after the fact goes in a changeset (`.changeset/*.md`).

| Change | What to do |
| --- | --- |
| A name, a path or a link is stale, or the prose breaks the [writing standard](../contributor/simplified-technical-english.mdx) | **Correct** it in place. Add no update block. Git history records the change. Do not change what was decided. |
| One rule or one consequence of the decision changed | **Update.** Append `## Update — YYYY-MM-DD: <what changed>` with the reason. Never rewrite the text above it. If another ADR made the change, add it to `**Amended by:**`. |
| The Decision no longer holds as written, or the ADR would get its third update | **Supersede.** Write a new ADR that states what is true now. Cite each ADR that it replaces. List their rejected options under "Already priced and declined", with links. Set the status of each old ADR to `Superseded by NNNN`, and change nothing else in it. |
| The decision no longer applies, and nothing replaces it | **Deprecate.** Set the status to `Deprecated` and append one update with the reason. |

A new ADR can change an old decision.
It then says what changed and why, and it cites a source for the new reason.
Rejected options are never deleted. A superseded ADR keeps them, and the new ADR links them.

### Checks

`scripts/decisions-index.test.ts` runs in `vp run check`. It checks that:

- each ADR has a unique number and a heading with that number.
- each status is in the table above.
- each ADR link in a header points to an existing ADR.
- each Accepted ADR from 0081 has a `## Confirmation` section.

## Index

The files of this directory are the index. Each file name starts with the ADR's number, and its header states its status.
A new ADR adds only its own file, so two pull requests that each add an ADR do not conflict.
To read the numbers, titles and statuses as one table, run `vp run report:decisions`.

- [ADR 0161](0161-a-verified-slack-dm-can-use-personal-context-with-a-grant.md) amends ADR 0123's Slack detail levels and DM fallback.

Template: [0000-template.md](0000-template.md).
