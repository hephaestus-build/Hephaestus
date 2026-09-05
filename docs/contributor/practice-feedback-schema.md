---
title: Practice feedback schema
description: The persisted shape of an observation and the feedback composed from it.
---

# Practice review data model

This page records the durable relationships and invariants behind practice reviews. It deliberately
does not maintain exhaustive implementation inventories.

Use the executable sources for exact details:

- [generated database schema](./database-schema.mdx) for tables, columns, keys, and relationships;
- [the `practices` module](https://github.com/hephaestus-build/Hephaestus/tree/main/server/application/src/main/java/de/tum/cit/aet/hephaestus/practices)
  for the domain model;
- [Liquibase changelogs](https://github.com/hephaestus-build/Hephaestus/tree/main/server/application/src/main/resources/db/changelog)
  for persisted constraints and indexes;
- [`openapi.yaml`](https://github.com/hephaestus-build/Hephaestus/blob/main/server/openapi.yaml) for HTTP projections;
- [ADR 0021](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0021-observations-feedback-synthesis-seam.md)
  and
  [ADR 0022](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0022-observation-presence-assessment-and-schema-cleanup.md)
  for design history;
- [practice feedback language](./practice-feedback-language.md) for user-facing terms.

## Model at a glance

| Concept               | Role                                                                       | Relationships                                                                   |
| --------------------- | -------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| `PracticeGroup`        | Workspace-defined grouping for practices                                   | Has many practices; a practice may be unassigned                                |
| `Practice`            | Configurable criterion used by detection                                   | Belongs to a workspace and may belong to a group                                |
| `PracticeRevision`    | Criteria snapshot used to interpret a past result                          | Belongs to one practice; an observation may pin one revision                    |
| `Observation`         | Evidence produced by one review job                                        | Belongs to one practice and one job; may support many pieces of feedback        |
| `Feedback`            | One recipient-specific piece of feedback and its delivery outcome          | Belongs to one job; may draw on many observations and have many placements      |
| `FeedbackApproval`    | Immutable decision to approve or reject one exact feedback proposal        | Stores the feedback ID, workspace, actor, decision context, content digest, and time |
| `FeedbackDispatch`    | Crash-safe provider delivery of one exact review package                   | Owns one job or approved feedback proposal; records provider placements before ledger projection |
| `FeedbackObservation` | Ordered evidence binding between one piece of feedback and one observation | Joins feedback to observations with a primary or supporting role                |
| `FeedbackPlacement`   | Where a piece of feedback was placed                                       | Belongs to one `Feedback`; records a summary, inline, or conversation placement |
| `Reaction`            | Legacy persistence name for an immutable feedback-response snapshot         | Belongs to one `Feedback`; stores usefulness, resolution, or both               |

## Invariants

### Evidence and feedback are separate

An observation records what a review detected. Feedback records the guidance prepared from one or more
observations and what happened to it. A placement records where that feedback appeared. This
separation preserves observations even when no feedback is composed or delivery is withheld.

Observation rows are immutable. Practice criteria are mutable, so each observation can reference the
criteria revision that applied to its review. Feedback content, provenance, and replacement link are
immutable. A re-review creates a new provider comment and a new feedback unit linked to the last delivered
unit; it never rewrites the earlier review. Guarded lifecycle updates may change delivery state, delivery
timestamp, or suppression reason.

### Occurrence and recurrence have different identities

`occurrenceKey` prevents duplicate persistence of the same result within a job retry. `recurrenceKey`
correlates the same evidence locus across review jobs. A new review therefore creates a new observation
even when it reports a recurring issue.

### The generation tool uses one outcome

`report_observation` asks the model for one `outcome`, rather than three fields whose valid combinations
the model must reconstruct. Behaviour outcomes encode occurrence, assessment, and—only for a bad
assessment—severity:

- `BEHAVIOR_PRESENT_GOOD`
- `BEHAVIOR_PRESENT_BAD_MINOR|MAJOR|CRITICAL`
- `BEHAVIOR_ABSENT_GOOD`
- `BEHAVIOR_ABSENT_BAD_MINOR|MAJOR|CRITICAL`
- `NO_REVIEW_OCCASION`: a prerequisite situation named by the practice did not occur
- `INSUFFICIENT_EVIDENCE`: the situation occurred, but the evidence read did not decide it

This is the model-facing contract. The normalizer maps it to the durable `presence`, `assessment`, and
`severity` columns so existing projections can query each dimension. `NO_REVIEW_OCCASION` maps to
`NOT_APPLICABLE`; `INSUFFICIENT_EVIDENCE` maps to `INCONCLUSIVE`. Do not expose the persistence names
in practice criteria or generation prompts: they are storage vocabulary, not choices the model makes.

### Each non-positive claim carries its proof shape

Every observation cites exact staged text. Outcomes that claim more than their citations also require
exactly one structured evidence branch:

| Outcome                 | Required branch             | What it records                                                     |
| ----------------------- | --------------------------- | ------------------------------------------------------------------- |
| `BEHAVIOR_ABSENT_*`     | `evidence.exhaustiveSearch` | sources searched, the concrete target, and the boundary not covered |
| `NO_REVIEW_OCCASION`    | `evidence.exclusion`        | sources read, the practice subject, and the fact that rules it out  |
| `INSUFFICIENT_EVIDENCE` | `evidence.missingEvidence`  | the open question and the existing evidence that would settle it    |
| `BEHAVIOR_PRESENT_*`    | none beyond citations       | the cited behaviour is the proof                                    |

A missing, errored, redacted, or inadequate **required source never becomes
`INSUFFICIENT_EVIDENCE`**. Readiness refuses that practice before generation, so no observation is
created. `INSUFFICIENT_EVIDENCE` is only for available evidence that was read and remained
non-dispositive.

An absent behaviour is a claim about a corpus. Every source a practice declares `EXHAUSTIVE` must appear
in `exhaustiveSearch.consulted`. `BEHAVIOR_ABSENT_GOOD` additionally requires at least one exhaustive
source, because “the harmful behaviour is nowhere here” is sound only over a bounded corpus covered
whole. The claim reaches no further than the recorded boundary; it is not a clean bill of health for the
repository or runtime. Both the sandbox normalizer and server admission enforce these rules.

### Ordering uses observable properties

Observations have no model-reported confidence score. `ObservationOrder` sorts by severity where it
applies, then by the number of distinct cited loci, then by stable identity. This makes ordering
deterministic and derives every input from admitted evidence instead of model self-assessment.

### Recipient and subject remain distinct

An observation's `aboutUserId` identifies the developer the evidence concerns. Feedback separately
stores the subject and the recipient. They may be equal, but they are not the same concept and must not
be inferred from each other.

### Delivery state is an audit fact

Each feedback row retains its delivery outcome. See
[Evaluation Provenance Contract](./evaluation-provenance.md) for state interpretation, evaluation joins,
and limitations.

`AWAITING_APPROVAL` is actionable feedback, not suppression. Its ordered package contains the exact
summary and inline comments. A guarded decision moves it once to `PREPARED` or terminal `DISCARDED` and
writes one `FeedbackApproval`; one approval covers the complete package. The digest binds the content,
recipient, channel, and artifact target, and release refuses a stale or mismatched decision. Approval
records retain identifier snapshots rather than JPA relationships so account erasure does not rewrite the
audit.

Provider creates use one durable dispatch for the complete review package. The dispatch stores the exact
content before egress and records each provider placement as it converges. A terminal package is projected
into feedback and placement rows under a separate lease, so a crash between provider acknowledgement and
ledger recording resumes projection without posting the package again.

### One feedback response has two independent dimensions

The recipient responds to the delivered `Feedback` unit, not to a private observation. One response may
record perceived usefulness (`HELPFUL` or `UNHELPFUL`), a resolution (`ADDRESSED`, `DISPUTED`, or
`NOT_APPLICABLE`), or both. `DISPUTED` requires a comment because it rejects the feedback's judgement;
usefulness alone does not change standing, trend, or re-nag suppression.

PUT replaces the complete response; omitted dimensions are cleared. DELETE removes the response. Both
operations are idempotent at the API boundary.

Response history is append-only in persistence. Read projections expose only the current response, so a
change of mind never counts as several currently resolved pieces of feedback.

## Read projections and access

The persistence model is not an authorization boundary. Controllers define who may see each projection:

- developer observation list, detail, and summary endpoints, and practice standings are scoped to the authenticated
  developer;
- the pull-request observation projection shows workspace members every relevant observation for that pull
  request;
- the workspace-admin practice-review endpoints expose observations and feedback across that workspace;
- developer-facing practice projections omit detector criteria by construction.

Keep these rules enforceable in controller authorization, repository predicates, DTO shape, and tests.
Do not add a field matrix here: the
[OpenAPI specification](https://github.com/hephaestus-build/Hephaestus/blob/main/server/openapi.yaml) is the
authoritative contract for fields exposed by each endpoint.
