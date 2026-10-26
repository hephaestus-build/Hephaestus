# ADR 0056: One practice model for items and scans

**Status:** Accepted
**Date:** 2026-10-14
**Authors:** Hephaestus maintainers

## Context

Practice reviews need one model across definitions, people, execution, results and feedback.
Scans also need durable selected-file membership after jobs expire.
Provider-specific records and duplicated definitions cannot express those facts safely.

## Decision drivers

- Keep every claim within captured and authorized evidence.
- Enforce workspace separation in SQL.
- Preserve product history independently of runtime cleanup.
- Use one vocabulary across code, interfaces and documentation.
- Give self-hosters a tested released-state migration.

## Considered options

1. **Keep provider-specific result and execution records.** Rejected. Job retention then deletes product history, and core repeats the meaning of each provider in its own enum.
2. **Add a parallel scan model.** Rejected. It costs two definition stores, two outcome enums and two report readers that must agree.
3. **Build a generic graph for future domains.** Rejected. Every query must join a node and edge schema. No shipped domain needs an edge beyond the relationships between items, reviews and results.
4. **One item, review and result model with domain meaning at the boundary.** Chosen.

## Decision

Use option 4.
A practice version owns one immutable authored definition.
The database generates its name and review digest.
Operational feedback mode is separate from Reviewed by.

Core owns neutral contracts, people, tenancy, reviews, result admission and feedback invariants.
Domain modules own kinds, versions, role maps, file selection and evidence meaning.
Provider adapters own authentication, APIs, events, roster sync, capture, delivery and deep links.
Core does not import a provider or diff model.
Domain citation verifiers check side, version and claim basis after generic quotation checks.

Accounts identify people across provider identities and workspaces.
Roster sync can create unclaimed human accounts before sign-in.
An authenticated identity can claim that record or absorb only an unclaimed account.
Existing active accounts are never merged without that boundary.
Membership history remains after logical removal.
Account erasure explicitly removes affected proof and feedback before membership.

A review owns product history and many independent execution jobs.
Durable review membership records selected, judged, reused and withheld practice/item pairs.
A result has one evidence-bounded outcome and optional located problems.
The current claim stays occupied when an admin marks it incorrect or accepted.
Neither mark ends the result or revives older proof.
Accepted results leave open-problem counts, without changing outcomes or establishing repair.

Feedback retains exact result/problem support and separate pieces at each destination.
Preparation persists physical copies before provider writes.
Unknown visibility stays Unconfirmed and never permits an automatic repeat POST.
A confirmed sibling retains its appearance time.
Practice-page delivery uses the feedback record's first authenticated developer read.

GitHub and GitLab scans pin monitored default-branch inventory.
Only one scan per workspace and one execution job per review can be active.
Reuse needs the same practice version, composed item version and complete frozen model method.
The composed file version includes the assigned blob and the declared context inventory.
An unread context file can therefore invalidate reuse.

Pinned inventories share a path index across selection and context grants.
Reuse makes one durable pass after the listing completes.
Files that cannot supply scan text remain in the inventory with an exclusion reason, outside eligible practice/file membership.

Incomplete or [suspect](../contributor/practice-review-pipeline.mdx#scans) inventory removes nothing.
File removal, marks and missed reads never prove repair or absence.
Slack and Outline support their own item evidence but have no scan driver or on-work feedback writer.

The [product vocabulary](../contributor/practice-feedback-language.md) owns product terms.
The [review glossary](../contributor/practice-review-glossary.mdx) owns operation terms and standard correspondence.
The [data model](../contributor/practice-feedback-schema.md) owns durable relationships.
The [pipeline](../contributor/practice-review-pipeline.mdx) owns stage boundaries.

## Consequences

All roles, runtime images and clients upgrade together.
One atomic migration transforms the released schema directly to this model.
It preserves retained identifiers, independent feedback pieces, unknown proof and provider write receipts.
The migration cancels queued jobs that use an unsupported runtime contract.
A digest does not reassess historical work.

The [migration procedure](../contributor/database-migration.mdx#prove-the-released-state-upgrade) owns upgrade verification.
Rollback restores a verified backup with the previous release.

This ADR supersedes model details in ADRs 0017, 0019, 0020, 0021, 0022, 0029 and 0050.
[ADR 0055](0055-practice-scripts-use-one-result-contract.md) owns the script contract.
Their surviving identity, evidence and runtime constraints remain in their owning records.

## Revisit trigger

Revisit when a shipped domain needs different person terms or citation coordinates.
Measured scan size can justify another storage design.
A new outcome needs a concrete evidence distinction that the current four cannot express.

### Dependency-based scan reuse

Reuse across different context inventories requires a complete dependency record from the host.
Complete coverage alone does not establish this record.
File-read receipts identify a path, pinned commit and content digest, but do not contain a Git blob ID.
The server can verify staged content against these receipts.
It must resolve the blob from the pinned tree before a blob can become a dependency.

The current script host returns listings and search matches without dependency receipts.
Tree citations also return text without a read receipt.
Successful operations can leave complete coverage with only the assigned file in the receipt list.
Aggregate read counts cannot distinguish a search from a repeated file read.
The receipt contract has no whole-tree marker or assertion that the dependency set is complete.
Thus, a missing receipt cannot establish that an input was unused.

A future dependency contract must include every path whose content the execution consumed, including citation text and absent paths.
A whole-tree listing or search must retain the declared context hash as a dependency.
The host must combine dependencies from the script and all its review sessions before admission.
Unknown or incomplete dependencies must retain the current version equality requirement.
Practice version, frozen model method, marks, trusted coverage and source authorization must still gate reuse.
The composed item version remains the identity for reports and comparison.

## Update — 2026-10-26: the item is the review scope

[ADR 0062](0062-the-item-is-the-review-scope.md) adds the quiet window, supersede and problem lineage on top of this model.
[ADR 0063](0063-each-practice-is-staged-when-it-ends.md) adds per-practice staging to review membership.
The item, version and current-claim model of this ADR stands.
