# ADR 0050: One practice standard, one outcome

**Status:** Accepted
**Date:** 2026-10-02
**Supersedes:** The observation axes in [ADR 0022](0022-observation-presence-assessment-and-schema-cleanup.md).

## Context

The presence/desirability matrix describes a behavior, but readers need conformance to a practice.
A desirable behavior being present does not prove the complete standard was met. The derived result
also requires every caller to reverse the meaning of absence correctly. The nested occasion list
suggests different evidence and subjects per signal even though the review operates on one definition.

## Decision drivers

- Make the result understandable without reversing presence and desirability.
- Distinguish lack of occasion, unresolved assessment, and failed capture.
- Preserve scientific records and reject unsupported equivalence claims.
- Use one model across persistence, runtime, API, UI, and evaluation.

## Considered options

1. Keep the matrix. Rejected: behavior classification is not full-standard conformance and duplicates
   interpretation across consumers.
2. Use only met/not met. Rejected: irrelevant work and unresolved evidence would become false
   failures or disappear from coverage accounting.
3. Use nullable conformance plus a separate status. Rejected: this retains multiple fields and invalid
   combinations without improving the four distinct facts.
4. Use one four-value outcome and flat review fields. Selected: the smallest model preserving the
   distinctions needed for accurate reporting and trustworthy feedback.

## Decision

Use one `outcome`: `MET`, `NOT_MET`, `NOT_APPLICABLE`, or `UNDETERMINED`. Criteria defines the positive
standard. Severity exists exactly for `NOT_MET`. Keep citations and claim-specific evidence warrants,
not a second behavioral classification. Capture failures create no observation.

Keep applicability separate from undecidability. Removing these distinctions would conflate no
occasion with unresolved assessment and distort the decided-result denominator. Reports disclose
both separately. Structural validation does not prove an assessment accurate.

Flatten practice review configuration into `signals`, `evidenceRequirements`, `reviewWhen`, `subject`,
and optional `precondition`. Keep `anyOf` in the precondition because alternatives are real logic,
not presentation nesting. Keep evidence requirements structured because source, quality, and stance
are distinct facts. Do not split free-text criteria into duplicate model-authored rubrics.

Perform one forward pre-1.0 cutover without fallback readers. Released migrations and original
research data remain immutable. Historical revisions with genuinely unknown metadata retain that
absence; new definitions are complete. Do not translate old empirical positive labels into claims
of full-standard conformance without an independently justified equivalence.

Automatic scheduling uses descriptor-owned finite state selections. `reviewWhen` has no duplicate
artifact-kind discriminator: signals already identify that family. `{}` is unrestricted; selected
values are alternatives within a dimension and all selected dimensions must match. Unsupported
states are rejected, and missing required facts do not match. A pull request's draft state is not
assigned to issues, documents or conversations. State is captured at admission for later preparation.

Scheduling (`signals`, `reviewWhen`) remains part of the immutable full definition digest but not
of the assessment fingerprint. It changes when to assess, not what the same evidence establishes.
Assessment preconditions remain in that fingerprint and are validated against descriptor capabilities.

## Consequences

All persistence, runtime, HTTP, UI, and evaluation consumers must change together. A verified backup
is required before upgrade. Feedback and delivery remain separate from observation outcomes.
A schema-valid model response can still be wrong; tests cover admission and evidence boundaries,
and research evaluates correctness against independently authored references.

## Revisit trigger

Revisit if independently reviewed evaluation cases establish that the four outcomes cannot express
an important assessment distinction, or if a shipped use case requires different subjects or evidence
contracts for signals within one practice. Do not add states only to encode feedback delivery.

## Evidence

- [SARIF 2.1.0](https://docs.oasis-open.org/sarif/sarif/v2.1.0/os/sarif-v2.1.0-os.html)
  distinguishes pass, fail, not applicable, and open results. This supports separation of result facts,
  not a claim that practice assessment has been empirically validated.
- [FEVER](https://arxiv.org/abs/1803.05355) distinguishes insufficient information from refutation.
  Its task differs from practice review; the useful principle is not treating unknown as failure.
- [OpenAI Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)
  constrains response shape, not factual correctness, and requires refusal handling.
- [React state structure](https://react.dev/learn/choosing-the-state-structure) recommends avoiding
  contradictory and redundant state. Editors keep one definition rather than mirrored occasion lists.
- [GitHub pull-request event payloads](https://docs.github.com/en/webhooks/webhook-events-and-payloads#pull_request)
  and the [GitLab merge-request API](https://docs.gitlab.com/api/merge_requests/) treat draft state and
  lifecycle as separate facts. A signal is not a substitute for either recorded fact.
- [Slack message events](https://docs.slack.dev/reference/events/message/) do not supply a shared
  pull-request lifecycle. A descriptor exposes only state its adapter records.
