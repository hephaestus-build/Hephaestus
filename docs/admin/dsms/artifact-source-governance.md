---
id: artifact-source-governance
sidebar_position: 4
title: Artifact-source governance
description: Approval, minimization, retention, and erasure gate for AI-readable sources.
---

# Artifact-source governance

An artifact source is a registered logical input that can supply project or personal data. Files, mounts, caches,
database projections, and tool results are materializations or derived copies: each must map to a registered source
and remain in the governance inventory. Code, configuration, documentation, and schema registration do not
authorize collection. Approve each source use for its exact purpose before enabling it.

The [artifact-source contract](../../contributor/artifact-source-contract) defines evidence semantics. This page
defines the governance decision that permits collection, retention, processing, and disclosure.

## Rules

1. **Default deny.** Approval must cover the source, purpose, audience, processor, region, and retention policy.
2. **Necessity before benefit.** Name the capability that requires the source and why a less intrusive source is
   insufficient. Accuracy, convenience, and possible reuse are not purposes.
3. **Minimize the catalog, not each invocation.** For an admitted review, the runtime captures every governed
   source that applies to that artifact kind. Practice bindings control whether a practice may use a source; they
   do not reduce what is staged for that invocation. Keep each source's selection scope and limits minimal, and
   remove a source from the next contract version when no approved consumer remains.
4. **Separate purposes.** Product feedback, mentoring, operator quality assurance, and research evaluation require
   separate decisions. Product use does not authorize evaluation retention or ablation.
5. **Separate responsibilities.** Workspace administrators connect integrations and enable practices within the
   shipped, operator-approved envelope; they do not approve sources, legal bases, processors, transfers, DPIA
   outcomes, or new data categories.
6. **Propagate restrictions.** Derivations inherit the strictest audience, egress, region, retention, and erasure
   rules of their dependencies. The runtime separately enforces `AUTOMATED_PRACTICE_REVIEW`,
   `PRACTICE_FEEDBACK_DELIVERY`, `CONVERSATIONAL_MENTORING`, and `OPERATOR_EVIDENCE_REVIEW` at their boundaries.
7. **Erasure beats replay.** Erasure or expiry may make a case unreplayable. Retain only a non-content tombstone
   where an approved audit purpose requires one.
8. **Fail closed on change.** Scope expansion remains disabled until every affected decision is approved.

## Required decision

| Review | Accountable role | Required evidence |
| --- | --- | --- |
| Necessity and minimization | Product owner and source maintainer | Consumers, exact fields and window, caps, alternatives |
| Lawful purpose and transparency | Controller; DPO when required | Legal basis, subjects, categories, notice, DPIA outcome |
| Processor and transfer | Controller or delegated privacy/procurement reviewer | DPA/AVV, role, region, subprocessors, transfer basis, retention and training terms |
| Security and access | Security reviewer | Trust boundary, tenant isolation, injection and secret controls, audience policy, safe logging |
| Retention and erasure | Data owner and integration maintainer | Expiry trigger, deletion owner, derived-data graph, export and erasure tests |
| Runtime contract | Agent/runtime maintainer | Schema, authority, identity anchoring, required capture quality, completeness, absence states, and contract tests |

A material change reopens the affected reviews. There is no deployment-level allowlist: the shipped, versioned
source-use decisions are the only gate, and no runtime configuration waives them. A source whose decision is
missing or expired is never read, whatever the deployment sets. Disabling a use therefore means shipping a
contract version in which that decision no longer permits it.

The runtime registry is
[`source-use-decisions.json`](https://github.com/hephaestus-build/Hephaestus/blob/main/server/application/src/main/resources/contracts/artifact-source/1.1.0/source-use-decisions.json).
It is an engineering gate and contains only releasable decision summaries. Each record governs exactly one source-use purpose; a source references separate records for automated review, feedback delivery, Mentor context, and operator evidence review:

- `ENGINEERING_BASELINE` with `ENGINEERING_APPROVED` records maintainer approval of the shipped, minimized
  product scope. It is not controller or DPO approval and cannot cover scope expansion. It is the only basis
  the contract can express: a use no maintainer has approved carries no record at all, rather than a record
  saying it was refused, so nothing here can ever be mistaken for a controller sign-off.

Every record carries a reviewer, a decision time, and an expiry, and the server refuses
to start if a source's decisions do not cover every product purpose or if a decision's retention or erasure
policy disagrees with its source.

Neither the registry nor CI can establish a legal basis, certify a DPIA, or replace the controller's record. Every
use requires its own unexpired decision for exactly that source and purpose.

`AGENT_EVIDENCE_RETENTION` separates persisted results from temporary review inputs. Diagnostic job output uses
`hephaestus.agent.payload-retention` (14 days by default); the job row and its manifest/readiness snapshot use
`hephaestus.agent.row-retention` (90 days). Worker input lifetime and deletion are defined by
[ADR 0041](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0041-compose-1x-kubernetes-2.md#evidence-admission-and-deletion). Verification results do
not authorize retaining complete inputs or replaying a review.

`WORKSPACE_AND_PERSON_ERASURE` is a governance obligation, not proof that every copy supports immediate selective
deletion. Controller approval must cover active attempts, disposable repository mirrors, backups, and retained
results. Person and channel requests use the source-specific paths in the processor checklist; any uncovered
derived copy blocks approval. The runtime and schemas use closed policy identifiers so a source cannot omit this decision.

## Decision record

Store the complete record in the controller's approved governance system. The repository may contain a releasable
summary and stable reference, but never participant data, private review notes, credentials, or sensitive samples.

The statuses in this template belong to the controller's governance system and are not the runtime registry's
vocabulary. The shipped registry expresses exactly one basis and one outcome — `ENGINEERING_BASELINE` with
`ENGINEERING_APPROVED` — so a controller's refusal is recorded there, in the controller's system, and reaches
the runtime as the absence of a permitting record rather than as a refusal the runtime can hold.

```yaml
decisionId: SRC-YYYY-NNN
status: PROPOSED # APPROVED, REJECTED, WITHDRAWN, SUPERSEDED
sourceKind: example.logical-source
sourceContractVersion: 1.1.0
deploymentScope: tumaet-production

sourceUse:
  purpose: AUTOMATED_PRACTICE_REVIEW
  consumers: [practice-slug]
  necessity: "Why a less intrusive source is insufficient"
  minimumScope: "Fields, query, event, window, ordering, and caps"

data:
  subjects: [contributors, reviewers]
  categories: [project-content, identifiers]
  incidentalSensitiveContent: "Controls for free text"
access:
  permittedRoles: [practice-review-runtime]
  developerDisclosure: "Permitted disclosure"
  tenantIsolation: "Enforcement and tests"
processorEgress:
  permitted: true
  processors: [approved-provider-binding]
  regions: [EU]
  trainingUse: prohibited
  providerRetention: "Contract reference"
  transferSafeguard: "Adequacy, SCC, or not applicable"
retention:
  product: "Duration and start event"
  evaluation: prohibited
  logs: "Typed codes and counts only"
erasure:
  disconnect: "Owner and trigger"
  workspacePurge: "Owner and trigger"
  personErasure: "Owner and trigger"
  derivedData: [cache, assessment, export, retained-case]
risk:
  dpiaReference: "Recorded determination"
  promptInjection: "Controls and residual risk"
  secretExposure: "Controls and residual risk"
  availabilityBias: "Potentially unobserved groups"
  misuse: "Grading, HR, ranking, or surveillance risks"
operations:
  owner: team-or-role
  killSwitch: "Control and runbook"
  healthSignal: "Low-cardinality metric and alert"
verification:
  schemaTests: []
  absenceStateTests: []
  inventoryTests: []
  tenantTests: []
  retentionAndErasureTests: []
approvals:
  product: { reviewer: null, decidedAt: null }
  privacy: { reviewer: null, decidedAt: null }
  security: { reviewer: null, decidedAt: null }
  dataOwner: { reviewer: null, decidedAt: null }
reviewBy: YYYY-MM-DD
supersedes: null
```

Create a separate record for each additional source-use purpose. Do not copy automated-review processor-egress terms into
feedback or operator review records when those uses do not invoke a model.

## Retention and erasure

Deletion must traverse every content-bearing copy and derived record. A deleted database row is insufficient if
the same content remains in a job directory, repository snapshot, precompute output, observation,
feedback record, export, backup, broker, or externally posted comment.

Before enabling a source or increasing retention, tests must prove:

- disconnect and workspace purge remove source data and derived workspace records without crossing tenant bounds;
- person erasure covers account-linked and source-only identities, conversations, assessments, feedback, exports,
  and retained cases;
- shared upstream objects remain only while another authorized workspace reference exists;
- disposable attempt folders and repository mirrors follow the worker cleanup and erasure contract;
- broker and backup expiry are documented when selective deletion is impossible;
- externally delivered content has a documented deletion or manual-remediation path; and
- tombstones contain no source content, identifiers, URLs, or reversible hashes.

Do not enable extended evaluation retention until its purpose, authorization, tenant isolation, retention, and
source/workspace/person erasure paths are implemented and tested.

## Approval renewal

The shipped decisions expire on the date recorded in
`server/application/src/main/resources/contracts/artifact-source/1.1.0/source-use-decisions.json`. Every governed use fails
closed after expiry. Instance operators should alert when
`artifact_source_governance_expiry_seconds` falls below 30 days and assign the alert to the instance
privacy/governance owner. The server logs a warning at startup inside the same window.

Before the deadline, that owner must review the source scopes, processors, retention, erasure coverage, and DPIA
record. Renewal is not an operator setting: it takes a release. Published contract versions are immutable, so a
renewed decision ships as a new contract version that the practice declarations are migrated onto, and an
existing version's dates are never edited. If renewal is denied or
incomplete, the expiry itself fails the use closed without any operator action: collection and disclosure stop,
the source is captured as `NOT_COLLECTED` with reason `GOVERNANCE_NOT_EFFECTIVE`, and Hephaestus makes no
automated review claim from it.

## Change checklist

- [ ] Define a stable logical kind, authority, selection scope, identity anchoring, required capture quality,
      completeness, caps, and absence states.
- [ ] Identify exact consumers and the least intrusive viable source.
- [ ] Update the Art. 30 record and privacy notice before collection.
- [ ] Record the DPIA determination and all required approvals.
- [ ] Approve processor, region, transfer, training, and provider-retention terms.
- [ ] Define operator, developer, and evaluation audiences and propagation rules.
- [ ] Define product, evaluation, log, cache, broker, and backup retention separately.
- [ ] Implement disconnect, purge, person erasure, expiry, export, and external-delivery handling.
- [ ] Inventory all files, mounts, caches, tools, and derivations; reject undeclared transformed views.
- [ ] Test every supported state, including valid empty evidence and applicable truncation or redaction.
- [ ] Use low-cardinality health metrics without workspace, repository, person, URL, or digest labels.
- [ ] Document the kill switch and operator remediation.
- [ ] Confirm the affected practices' review-rule fingerprints change, so their earlier claims derive as stale
      rather than continuing to read as current. Nothing marks a claim stale by hand.
- [ ] Link the approved decision from the source descriptor.

The TUM deployment's current Art. 35 status and expansion restrictions are recorded in the
[DPIA pre-screen](./dpia-prescreen.md). A source-use registry entry does not override those restrictions.

## References

- [GDPR Article 5](https://eur-lex.europa.eu/eli/reg/2016/679/art_5/oj)
- [GDPR Article 25](https://eur-lex.europa.eu/eli/reg/2016/679/art_25/oj)
- [GDPR Article 30](https://eur-lex.europa.eu/eli/reg/2016/679/art_30/oj)
- [GDPR Article 35](https://eur-lex.europa.eu/eli/reg/2016/679/art_35/oj)
- [WP29 Guidelines on DPIA, WP248 rev.01](https://ec.europa.eu/newsroom/article29/items/611236/en)
- [NIST Privacy Framework](https://www.nist.gov/privacy-framework)
- [NIST Generative AI Profile](https://doi.org/10.6028/NIST.AI.600-1)
