# ADR 0064: One evidence contract for every result producer

**Status:** Accepted
**Date:** 2026-10-26
**Authors:** Felix T.J. Dietrich

**Builds on:** [ADR 0055](0055-practice-scripts-use-one-result-contract.md). Its candidate shape stays.

## Context

A result can come from three producers: code in a practice script, a model session with tools, or a classifier call.
ADR 0055 gave them one candidate shape.
It did not give them one rule for what makes a candidate trustworthy.

Review sessions got host coverage: the host counted the reads, and required and cited sources had to be read in full.
Code got a second form, claimed coverage: the script typed the counts.
Until [#3277](https://github.com/hephaestus-build/Hephaestus/pull/3277), admission accepted claimed complete coverage for Met and Does not apply on items.
A script that ignored a failed read could still report Met.
That turns a capture failure into a claim about the work.

## Decision drivers

- Never turn a capture failure into a claim about someone's work (non-negotiable 1).
- A result with problems must be possible without a model.
- One rule per fact, for every producer.
- No project commands, such as a build or a test run, in the sandbox for 1.0. Every producer reads captured sources.

## Considered options

1. **Keep claimed coverage for code.** Rejected. It is the gap.
2. **A separate trust level for code-built results.** Rejected. Readers would need to know the producer to trust a result.
3. **The host measures coverage for every candidate, by the rule that review sessions follow.** Chosen.

## Decision

The evidence contract is the same for every producer:

- **Coverage.** The host measures it from the producer's own reads. A candidate cannot claim it.
  A script returns what `ctx.review` returned, or a result that its code decided, without coverage.
  A result keeps a review's coverage only when it returns that review's outcome and citations unchanged.
  Any other result is a decision of code, whatever coverage it carries.
  For code, each required source and each cited source needs a complete read. The captured change counts as supplied in full.
  A citation alone is not a read, because it returns only the quoted lines.
  A truncated or failed read makes coverage incomplete. Code that read nothing covers nothing.
  A Does not apply that code decided needs complete coverage.
  Admission refuses claimed coverage on every outcome.
- **Citations.** Every citation quotes captured text. Admission reads the quote again from the captured source.
- **Claim basis.** Every problem has a basis that the domain verifier checks against the pinned versions.
- **Absence.** A claim that something is absent needs a complete source. An undecidable condition keeps a practice eligible; it never proves Does not apply.
- **Text.** Code may write a result's text. Composition rewrites it for each destination from the sealed result.

The [script runner guide](../contributor/practice-script-runner.mdx) owns candidate fields and limits.
The [pipeline](../contributor/practice-review-pipeline.mdx#admit-results) owns admission.

## Consequences

- A code-only practice can produce Met, Not met and Does not apply without a model, on the same trust as a review session.
- A script cannot hide a failed read behind its own counts.
- Claimed coverage stays only as a stored form of earlier results. A custom script that returns coverage stops as an invalid result.
- Per-criterion absence checks (`criterion.where`) and classifier receipts with a calibration gate are the next parts of this contract. They need the practice definition to name its criteria. The maintainers own the criteria.

## Revisit trigger

Revisit when a producer cannot report reads through the host, for example a hermetic analyser in the agent image.
