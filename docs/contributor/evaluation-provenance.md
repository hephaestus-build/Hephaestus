---
title: Evaluation provenance
description: Trace a persisted practice observation to its review inputs, configuration, and delivery evidence.
---

# Evaluation provenance

This page describes the provenance available for automated practice reviews. The Java model and
[Liquibase changelogs](https://github.com/hephaestus-build/Hephaestus/tree/main/server/application/src/main/resources/db/changelog)
own the persisted contract; the [generated database schema](./database-schema.mdx) shows its structure.

## Review provenance

Each automated practice-review invocation has an `agent_job`. Provenance is captured when it becomes
stable:

| Dimension | Stored in | Captured at |
| --- | --- | --- |
| Behaviour configuration | `agent_job.config_snapshot` | submission and claim |
| Prompt scaffolding | `agent_job.prompt_digest` | preparation |
| Injected files | `agent_job.inputs_digest` | preparation |
| Repository revision, where applicable | job metadata and the evidence manifest | submission and preparation |
| Admitted practice criteria | evidence snapshot and `observation.practice_revision_id` | preparation and persistence |
| Model identity, usage, and cost | `agent_job` and `llm_usage_event` | completion |

Use the complete versioned configuration snapshot as the behaviour-configuration identity. A selected
tuple of model and endpoint fields is not equivalent to the snapshot.

`prompt_digest` identifies the shipped prompt scaffolding. `inputs_digest` identifies the final map of
files injected by the executor, excluding repository mounts and runtime-created files. It is
path-order-independent and elides occurrences of the job UUID. Equal digests therefore do not imply
byte-identical sandbox workspaces or semantically equivalent evidence.

The evidence snapshot records the exact admitted practice revision. Persistence carries that identity
into `observation.practice_revision_id`; it is not reconstructed from a timestamp.

Comparisons must be stratified by the complete behaviour configuration, prompt digest, input or case
cohort, and practice revision. Equality in one dimension does not make runs comparable in the others.

## Delivery evidence

Every composed feedback unit that reaches the delivery layer has a `feedback` row. A feedback state is a
delivery-policy outcome, not proof of an external placement or of human exposure. Prove placement from
`feedback_placement` or dispatch evidence. A placement still does not prove that its recipient read or
acted on the feedback.

The Java
[`FeedbackDeliveryState`](https://github.com/hephaestus-build/Hephaestus/blob/main/server/application/src/main/java/de/tum/cit/aet/hephaestus/practices/feedback/FeedbackDeliveryState.java)
and
[`FeedbackSuppressionReason`](https://github.com/hephaestus-build/Hephaestus/blob/main/server/application/src/main/java/de/tum/cit/aet/hephaestus/practices/feedback/FeedbackSuppressionReason.java)
types define delivery outcomes and suppression reasons.

## Evaluation joins

- **Observation to review:** join `observation.agent_job_id` to `agent_job` for configuration, digests,
  repository metadata, and usage. Join `observation.practice_revision_id` to the admitted criteria.
- **Observation to feedback:** join through `feedback_observation`. Absence of a link means no feedback
  was composed from that observation; it says nothing about why.
- **Feedback to placement:** join `feedback_placement` and inspect its channel reference. Feedback state
  alone is insufficient evidence of placement.
- **Response to observation:** join `reaction.feedback_id` through `feedback_observation`. The latest
  append-only reaction records the response fields that were provided; missing telemetry is unknown,
  not a negative response.

## Interpretation limits

- Digests identify materialised bytes under their stated coverage; they do not establish semantic
  equivalence or cover every sandbox-visible input.
- Persisted observations exclude candidates rejected before persistence. Invalid-output and failure-rate
  analysis requires a durable attempt and transition record rather than logs or observation rows alone.
- Delivery and placement evidence does not establish that feedback was read or changed behaviour.
