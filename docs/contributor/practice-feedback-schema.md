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
| `Practice`            | Configurable criterion a review checks                                     | Belongs to a workspace and may belong to a group                                |
| `PracticeRevision`    | Criteria snapshot used to interpret a past result                          | Belongs to one practice; an observation may pin one revision                    |
| `Observation`         | Evidence produced by one review job                                        | Belongs to one practice and one job; may support many pieces of feedback        |
| `Feedback`            | One recipient-specific piece of feedback and its delivery outcome          | Belongs to one job; may draw on many observations and have many placements      |
| `FeedbackApproval`    | Immutable decision to approve or reject one exact feedback proposal        | Stores the feedback ID, workspace, actor, decision context, content digest, and time |
| `FeedbackDispatch`    | Crash-safe provider delivery of one exact review package                   | Owns one job or approved feedback proposal; records provider placements before ledger projection |
| `FeedbackObservation` | Ordered evidence binding between one piece of feedback and one observation | Joins feedback to observations with a primary or supporting role                |
| `FeedbackPlacement`   | Where a piece of feedback was placed                                       | Belongs to one `Feedback`; records a summary, inline, or conversation placement |
| `Reaction`            | Immutable feedback-response snapshot                                       | Belongs to one `Feedback`; stores usefulness, resolution, or both               |

## Invariants

### Evidence and feedback are separate

An observation records what a review observed. Feedback records the guidance prepared from one or more
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
even when it reports a recurring locus.

### One contract from generation to storage

`report_observation`, normalized runtime output, server admission, persistence and read DTOs use the
same `assessmentStatus`, `presence`, `assessment` and `severity` axes. The
[product vocabulary](./practice-feedback-language.md#observation-assessment-axes) defines their valid
combinations and meaning. There is no fused outcome enum or translation to different presence labels.
The runtime requires every axis explicitly, including nulls. Contradictory axes are rejected by the
normalizer and server and constrained by the database; no judgment or severity is silently invented.

### Evidence warrants

Every observation cites exact staged text. Exactly one additional warrant is required where specified:

| Claim | Required branch | What it records |
| --- | --- | --- |
| ASSESSED / ABSENT | `evidence.search` | sources searched, fixed target and search boundary |
| NOT_APPLICABLE | `evidence.inapplicability` | sources read, prerequisite subject and the fact ruling it out |
| UNDETERMINED | `evidence.undecidability` | open question and what would settle it |
| ASSESSED / PRESENT | none beyond citations | the cited target itself |

A missing, errored, redacted or inadequate required source is a readiness failure, not UNDETERMINED.
No observation is created for that practice. Read available evidence before claiming ambiguity.

Every source declared `EXHAUSTIVE` must appear in `search.consulted`. ABSENT / GOOD additionally
requires at least one exhaustive source: avoiding a harmful target is provable only over an applicable,
bounded corpus searched completely. It does not prove correctness beyond the recorded boundary.
Both the sandbox and server admission enforce evidence requirements.

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
- developer-facing practice projections omit review rules by construction.

Keep these rules enforceable in controller authorization, repository predicates, DTO shape, and tests.
Do not add a field matrix here: the
[OpenAPI specification](https://github.com/hephaestus-build/Hephaestus/blob/main/server/openapi.yaml) is the
authoritative contract for fields exposed by each endpoint.
