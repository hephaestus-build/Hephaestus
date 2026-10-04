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
- [ADR 0021](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0021-observations-feedback-synthesis-seam.md),
  [ADR 0050](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0050-one-practice-standard-one-outcome.md)
  and
  [ADR 0051](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0051-practices-decide-from-answered-questions.md)
  for design history;
- [practice feedback language](./practice-feedback-language.md) for user-facing terms.

## Model at a glance

| Concept               | Role                                                                       | Relationships                                                                   |
| --------------------- | -------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| `PracticeGroup`        | Workspace-defined grouping for practices                                   | Has many practices; a practice may be unassigned                                |
| `Practice`            | Configurable criterion a review checks                                     | Belongs to a workspace and may belong to a group                                |
| `PracticeRevision`    | Criteria and judgment snapshot used to interpret a past result             | Belongs to one practice; an observation may pin one revision                    |
| `Observation`         | Answers and derived evidence produced by one review job                    | Belongs to one practice and one job; may support many pieces of feedback        |
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

Observation rows are immutable. Practice criteria and judgments are mutable, so each observation can
reference the revision that applied to its review; its answers and `rule_id` are read against that
revision's questions and rules, never the current ones. Feedback content, provenance, and replacement link are
immutable. A re-review creates a new provider comment and a new feedback unit linked to the last delivered
unit; it never rewrites the earlier review. Guarded lifecycle updates may change delivery state, delivery
timestamp, or suppression reason.

### Occurrence and recurrence have different identities

`occurrenceKey` prevents duplicate persistence of the same result within a job retry. `recurrenceKey`
groups the same evidence location across review jobs, including observations about different behaviors.
A new review creates a new observation. Location grouping proves neither recurrence nor resolution;
responses and exact-observation delivery decisions use the observations bound to the feedback.

### Historical measurement is not current conformance

A result from the earlier behavior-level scheme does not establish conformance under the current
whole-standard contract. Historical revision fingerprints are preserved. A missing or incompatible
fingerprint makes a claim `UNVERIFIABLE`; it cannot support current standings or new delivery.
New reviews append a current-scheme snapshot without rewriting the revision of a past observation.

### One contract across boundaries

An observation carries one required `outcome`: `MET`, `NOT_MET`, `NOT_APPLICABLE`, or `UNDETERMINED`.
The [product vocabulary](./practice-feedback-language.md#observation-outcomes) owns their meaning.
The runtime submits answers, never an outcome; admission derives the outcome, the severity and the
deciding rule from the answers with the pinned revision's judgment
([derivation](./practice-review-glossary.mdx#deriving-the-outcome)). Persistence and HTTP projections
use the same values. Severity — `CRITICAL`, `MAJOR` or `MINOR` — is present exactly for `NOT_MET`.
Admission rejects incomplete or contradictory answers rather than deleting fields or manufacturing a
judgment. Delivery policy remains independent of the observation outcome.

`observation.answers` holds one entry per answered question, none for a
[skipped question](./practice-review-glossary.mdx#deriving-the-outcome): the answer, its reason, the indexes of the
citations it rests on, and its search or what would settle it. `observation.rule_id` names the deciding
rule and is null when open answers left the outcome undetermined. Observations recorded before reviews
answered questions have neither.

### Evidence warrants

Every answer cites exact staged text, and the observation's citations are the union of its answers'
citations, the deciding answers' first. Admission derives the other warrants from the deciding answers:

| Claim | Evidence branch | Meaning |
| --- | --- | --- |
| A decided claim whose deciding answer rests on absence | `evidence.search` | Sources searched, target, and bounded scope, merged from those answers |
| `NOT_APPLICABLE` | `evidence.inapplicability` | Sources read, the deciding questions, and the deciding rule's reason |
| `UNDETERMINED` | `evidence.undecidability` | The open questions, or the deciding rule's reason, and what would settle them |
| Directly evidenced conformance or shortfall | Citations | The actual work supporting the claim |

`evidenceRationale` is derived too: the deciding rule's reason — or, with no deciding rule, the titles
of the questions left open — then the deciding answers' reasons.

A missing, errored, redacted, or inadequate required source is a readiness failure, not an outcome.
Every source declared exhaustive must appear in the search of an answer resting on absence. A `MET`
claim based on absence requires at least one exhaustive source. This proves conformance only within
the recorded boundary. The runner and admission validate search coverage; neither structural
validation nor a schema proves the truth or completeness of a model's answer. Empirical evaluation must
test those properties separately, question by question.

### Ordering uses observable properties

Observations have no model-reported confidence score. `ObservationOrder` sorts by the derived severity where it
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
