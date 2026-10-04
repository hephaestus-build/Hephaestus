---
id: artifact-source-governance
sidebar_position: 4
title: Artifact-source governance
description: Approval, minimization, retention, and erasure gate for AI-readable sources.
---

# Artifact-source governance

An artifact source is a registered logical input that can supply project or personal data. Files, mounts, caches, database projections, and tool results are materializations or derived copies. Each must map to a registered source and remain in the governance inventory. Code, configuration, documentation, and schema registration do not
authorize collection. Approve each source use for its exact purpose before enabling it.

The [artifact-source contract](../../contributor/artifact-source-contract) defines evidence semantics. This page
defines the governance decision that permits collection, retention, processing, and disclosure.

## Rules

1. **Default deny.** Approval must cover the source, purpose, audience, processor, region, and retention policy.
2. **Necessity before benefit.** Name the capability that requires the source and why a less intrusive source is
   insufficient. Accuracy, convenience, and possible reuse are not purposes.
3. **Minimize the catalog, not each invocation.** For an admitted review, the runtime captures every governed
   source in the permitted workspace scope. Practice evidence requirements control whether a practice may use a source. They
   do not reduce what is staged for that invocation. Keep each source's selection scope minimal.
   If no approved consumer remains, remove the source from the next contract version.
4. **Separate purposes.** Product feedback, mentoring, operator quality assurance, and research evaluation require
   separate decisions. Product use does not authorize evaluation retention or ablation.
   Research use requires the participant's current research grant and a recorded controller decision for the purpose.
   Product use does not authorize it.
5. **Separate responsibilities.** Workspace administrators connect integrations and enable practices within the
   shipped, operator-approved envelope. They do not approve sources, legal bases, processors, transfers, DPIA
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
| Lawful purpose and transparency | Controller, with the DPO when required | Legal basis, subjects, categories, notice, DPIA outcome |
| Processor and transfer | Controller or delegated privacy/procurement reviewer | DPA/AVV, role, region, subprocessors, transfer basis, retention and training terms |
| Security and access | Security reviewer | Trust boundary, tenant isolation, injection and secret controls, audience policy, safe logging |
| Retention and erasure | Data owner and integration maintainer | Expiry trigger, deletion owner, derived-data graph, export and erasure tests |
| Runtime contract | Agent/runtime maintainer | Schema, authority, identity anchoring, required capture quality, completeness, absence states, and contract tests |

A material change reopens the affected reviews. There is no deployment-level allowlist: the shipped, versioned
source-use decisions are the only gate, and no runtime configuration waives them. A source whose decision is
missing or expired is never read, whatever the deployment sets. Revoking its per-source engineering permission requires a
contract version in which that decision no longer permits it. For an immediate legal or security stop, use one of these actions:

1. Pause the affected feature.
2. Alternatively, disconnect its source.

Existing registry permission is not an operator instruction to keep processing.

The runtime registry is
[`source-use-decisions.json`](https://github.com/hephaestus-build/Hephaestus/blob/main/server/application/src/main/resources/contracts/source-use/1.3.0/source-use-decisions.json).
It is an engineering gate and contains only releasable decision summaries. Each record governs exactly one source-use purpose. A source references separate records for automated review, feedback delivery, Mentor context, and operator evidence review:

- `ENGINEERING_BASELINE` with `ENGINEERING_APPROVED` records maintainer approval of the shipped, permitted product scope.
  This includes scope expansions that the maintainer explicitly approves in a new version.
  It is not controller or DPO approval.
  It is the only basis the contract can express.
  A use with no maintainer approval has no record, rather than a refusal record.
  The controller must record its decision separately.

### Full job-folder scope

On 2026-10-01, the maintainer explicitly approved the engineering scope in [#1732](https://github.com/hephaestus-build/Hephaestus/issues/1732). It covers every permitted workspace area and repository, including Slack threads and person-scoped observation and feedback history. It has no record-count or history-window caps.

Version 1.3.0 records this approval for automated practice review. It does **not** record controller or DPO
approval. The existing visibility, consent, withdrawal, tenancy, processor, retention and erasure checks still apply. The engineering implementation ships in [#2335](https://github.com/hephaestus-build/Hephaestus/pull/2335). The full DPIA is indicated for this combined scope. A versioned engineering decision is not authority to activate it for TUM.

The folder is not permission to disclose a refused record or to retain a source after its deletion boundary.

The member AI choice gates reviews about the developer and access to their person-scoped history.
It is not a per-person filter on shared repository context: another developer's review can still
read that content. Treat this separately in necessity, transparency and objection handling. Do not
present No AI as removal from every model input.

The wider Slack-thread and person-scoped history scope must be part of the pending TUM privacy-notice review in
[#1377](https://github.com/hephaestus-build/Hephaestus/issues/1377). That legal-review item remains open and is owned
by the maintainer. Engineering approval does not close it.

Every record carries a reviewer, a decision time, and an expiry. The server refuses to start if a source's decisions do not cover every product purpose. It also refuses to start if a decision's retention or erasure policy disagrees with its source.

Neither the registry nor CI can establish a legal basis, certify a DPIA, or replace the controller's record. Every
use requires its own unexpired decision for exactly that source and purpose.

`AGENT_EVIDENCE_RETENTION` separates persisted results from temporary review inputs. Diagnostic job output uses
`hephaestus.agent.payload-retention` (14 days by default). The job row and its manifest/readiness snapshot use
`hephaestus.agent.row-retention` (90 days). Worker input lifetime and deletion are defined by
[ADR 0041](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0041-compose-1x-kubernetes-2.md#evidence-admission-and-deletion). Verification results do
not authorize retaining complete inputs or replaying a review.

`WORKSPACE_AND_PERSON_ERASURE` is a governance obligation, not proof that every copy supports immediate selective
deletion. Controller approval must cover active attempts, disposable repository mirrors, backups, and retained
results. Person and channel requests use the source-specific paths in the personal-data map. Any uncovered
derived copy blocks approval. The runtime and schemas use closed policy identifiers so a source cannot omit this decision.

## Decision record

Store the complete record in the controller's approved governance system. The repository may contain a releasable
summary and stable reference, but never participant data, private review notes, credentials, or sensitive samples.

The statuses in this template belong to the controller's governance system and are not the runtime registry's
vocabulary. The shipped registry expresses exactly one basis and one outcome — `ENGINEERING_BASELINE` with
`ENGINEERING_APPROVED` — so a controller's refusal is recorded in the controller's system. Operators must stop the affected use or integration while a release removes its engineering permission. The existing registry does not automatically ingest a legal refusal.

```yaml
decisionId: SRC-YYYY-NNN
status: PROPOSED # APPROVED, REJECTED, WITHDRAWN, SUPERSEDED
sourceKind: example.logical-source
sourceContractVersion: 1.3.0
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

Deletion must traverse every content-bearing copy and derived record. A deleted database row is insufficient if the same content remains in any of these copies:

- Job directory.
- Repository snapshot.
- Precompute output.
- Observation.
- Feedback record.
- Export.
- Backup.
- Broker.
- Externally posted comment.

Before enabling a source or increasing retention, tests must prove:

- Disconnect and workspace purge remove source data and derived workspace records without crossing tenant bounds.
- Person erasure covers account-linked and source-only identities, conversations, assessments, feedback, exports,
  and retained cases.
- Shared upstream objects remain only while another authorized workspace reference exists.
- Disposable attempt folders and repository mirrors follow the worker cleanup and erasure contract.
- Broker and backup expiry are documented when selective deletion is impossible.
- Externally delivered content has a documented deletion or manual-remediation path.
- Completed request receipts contain no erased content or subject keys. Minimal exact native identity
  keys remain separately as permanent processing-suppression controls. They are personal data with a
  distinct prevention purpose, not anonymous tombstones.

Do not enable extended evaluation retention until its purpose, authorization, tenant isolation, retention, and
source/workspace/person erasure paths are implemented and tested.

## Approval renewal

The shipped decisions expire on the date recorded in
`server/application/src/main/resources/contracts/source-use/1.3.0/source-use-decisions.json`. Every governed use fails
closed after expiry. Instance operators should alert when `artifact_source_governance_expiry_seconds` falls below 30 days.
They should assign the alert to the instance privacy/governance owner. The server logs a warning at startup inside the same window.

Before the deadline, that owner must review the source scopes, processors, retention, erasure coverage, and DPIA
record. Renewal is not an operator setting: it takes a release. Published contract versions are immutable. A renewed decision ships as a new contract version. The practice declarations migrate to that version. An existing version's dates never change.

If renewal is denied or incomplete, expiry fails the use closed without any operator action. Collection and disclosure stop. The runtime captures the source as `NOT_COLLECTED` with reason `GOVERNANCE_NOT_EFFECTIVE`. Hephaestus makes no automated review claim from it.

## Change checklist

1. [ ] Define a stable logical kind, authority, selection scope, identity anchoring, required capture quality,
      completeness, caps, and absence states.
2. [ ] Identify exact consumers and the least intrusive viable source.
3. [ ] Update the Art. 30 record and privacy notice before collection.
4. [ ] Record the DPIA determination and all required approvals.
5. [ ] Approve processor, region, transfer, training, and provider-retention terms.
6. [ ] Define operator, developer, and evaluation audiences and propagation rules.
7. [ ] Define product, evaluation, log, cache, broker, and backup retention separately.
8. [ ] Implement disconnect, purge, person erasure, expiry, export, and external-delivery handling.
9. [ ] Inventory all files, mounts, caches, tools, and derivations.
      Reject undeclared transformed views.
10. [ ] Test every supported state, including valid empty evidence and applicable truncation or redaction.
11. [ ] Use low-cardinality health metrics without workspace, repository, person, URL, or digest labels.
12. [ ] Document the kill switch and operator remediation.
13. [ ] Confirm that the affected practices' review-rule fingerprints change.
      Their earlier claims then derive as stale, rather than continuing to read as current.
      Nothing marks a claim stale by hand.
14. [ ] Link the approved decision from the source descriptor.

The TUM deployment's current Art. 35 status and expansion restrictions are recorded in the
[DPIA pre-screen](./dpia-prescreen.md). A source-use registry entry does not override those restrictions.

## References

- [GDPR Article 5](https://eur-lex.europa.eu/eli/reg/2016/679/oj)
- [GDPR Article 25](https://eur-lex.europa.eu/eli/reg/2016/679/oj)
- [GDPR Article 30](https://eur-lex.europa.eu/eli/reg/2016/679/oj)
- [GDPR Article 35](https://eur-lex.europa.eu/eli/reg/2016/679/oj)
- [WP29 Guidelines on DPIA, WP248 rev.01](https://ec.europa.eu/newsroom/article29/items/611236/en)
- [NIST Privacy Framework](https://www.nist.gov/privacy-framework)
- [NIST Generative AI Profile](https://doi.org/10.6028/NIST.AI.600-1)
