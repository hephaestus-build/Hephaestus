# ADR 0022: Observation = presence × assessment (drop `Practice.kind`); reaction anchors on feedback; ruthless column cleanup

**Status:** Accepted; observation semantics amended before 1.0
**Date:** 2026-06-24
**Authors:** Felix T.J. Dietrich
**Supersedes (in part):** [ADR 0021](0021-observations-feedback-synthesis-seam.md) F-6 (the sign-neutral `Observation` × `Practice.kind` split) and F-13/F-24 (the `FeedbackReaction` reshape with a nullable `finding_id` and an open `verb` event log)

## Context

ADR 0021 F-6 modelled an evaluation as two columns: a sign-neutral `PracticeFinding.observation`
(`OBSERVED` / `NOT_OBSERVED` / `NOT_APPLICABLE`) times a direction `Practice.kind`
(`GOOD_PRACTICE` / `BAD_PRACTICE` / `CONTEXTUAL`), reconciled by `PracticeKind.isProblem(...)`.

Two problems surfaced. First, the direction-on-the-rule indirection is non-standard: SARIF — the dominant
cross-tool result format — carries the good/bad outcome on the *result* (`result.kind` = `pass`/`fail`),
not on the rule; there is no rule-direction property. Second, and more important, a single resolved enum
conflates **two genuinely different questions**:

1. **presence** — did the detector see the target signal?
2. **assessment** — is that good or bad for the developer?

Collapsing them loses the distinction between a gap by **omission** (a good behaviour is missing) and a
problem by **commission** (a bad behaviour is present) — a pedagogically real difference — and it cannot
represent a practice with both good and bad aspects.

## Decision

### 1. Split the evaluation into two orthogonal columns on the observation; drop `Practice.kind`

The current contract is owned by
[practice feedback language](../contributor/practice-feedback-language.md#observation-assessment-axes).
The pre-1.0 amendment separates assessment status from presence and fixes assessment to the target's
desirability. POSITIVE/NEGATIVE is derived from presence × assessment. Severity is populated exactly
for negative outcomes. Neither unassessed status is a verdict or a substitute for failed capture.

This replaces the earlier convention in this ADR where GOOD/BAD alone encoded the verdict. The
migration swaps assessment on historical ABSENT rows to preserve their original outcome and severity;
it does not reinterpret their evidence against newly revised criteria. Frozen evaluation reports must
retain their declared historical convention. Newly annotated datasets use an explicit schema version.

### 2. The reaction anchors on feedback only

`FindingReaction` → `reaction`, anchored on `feedback_id` (NOT NULL). `finding_id` and its mirror are
removed entirely — no backward compatibility. A developer reacts to the *delivered feedback*, never to a
private observation. The action stays a closed enum (`ADDRESSED` / `DISPUTED` / `NOT_APPLICABLE`); an open
verb / event log is rejected (no second producer; it turns the uptake metric into free-text). `DISPUTED`
requires an explanation. The historical `reaction.recurrence_key` column remains for schema compatibility,
but current re-nag suppression follows the response through `feedback_observation` and matches the bound
observation's `recurrence_key`. That keeps the response anchored only to what the developer received while
using the observation as the authoritative locus.

### 3. Identity collapses to the minimal correct set

- Observation keeps `about_user_id` only; `developer` (a 3NF transitive dependency on the contribution's
  author) is dropped. The feedback recipient is re-sourced from `about_user_id`, so dropping `developer`
  does not reopen the reviewer-side firewall.
- Feedback keeps `about_user_id` (who it is about) **and** `recipient_user_id` (who it is delivered to);
  equal today, but the split is the firewall and `recipient_user_id` is read by the thread-key derivation.
- Reaction keeps one identity, `reactor_user_id`.

### 4. Cut columns with no reader (ruthless, evidence-bound)

Drop `observer` (always `SYSTEM`, zero readers); feedback `idempotency_key` (duplicate of the
`(agent_job_id, position)` unique), `model_id`, `composer_version` (write-only), `synthesis_prompt_version`
(dead); placement `anchor_old_path` / `anchor_start_side` / `anchor_quote` / `pinned_commit_sha`
(never written), `posted_state` / `thread_external_ref` (written, no reader), `resolved*` (dead).
Keep the observation `occurrence_key` (per-occurrence dedup grain, distinct from the `recurrence_key`
locus grain). `replaces_id` and `thread_key` are kept: the supersession chain backs the thread-key
derivation and the on-read `baseline_state`.

### 5. Human-readable names + entity renames

`PracticeFinding` → `Observation` (table `observation`); `FeedbackFinding` → `FeedbackObservation`;
`FindingReaction` → `reaction`. Column renames for clarity: `observation`→`presence`+`assessment`,
`finding_fingerprint`→`recurrence_key`, `idempotency_key`→`occurrence_key`, `subject_user_id`→
`about_user_id`, `rendered_body`→`body`, `surface`→`channel` (and `REFLECTION_DASHBOARD`→`PROFILE`; that
channel value was later renamed again to `REFLECTION`, because `PROFILE` collided with the public user
profile and named a storage location rather than how the developer engages — see `FeedbackChannel`),
`provenance`→`source`, `unit_ordinal`→`position`, `supersedes_id`→`replaces_id`,
`feedback_thread_key`→`thread_key`, `slot`→`placement_type`, `external_ref`→`posted_comment_ref`,
`evidence_role`→`role`, `detected_at`→`observed_at`.

`Practice.kind` and the observation `observer` column are transient migration scaffolding — intermediate columns that never ship in the final schema; outcome is derived from `presence` × `assessment` and the observer was always `SYSTEM`.

## Consequences

- The schema speaks plainly and stops conflating measurement with evaluation: omission vs commission is
  recoverable from `(presence, assessment)`, with one fixed target per practice revision.
- Direction is no longer a rule column; every former reader of `Practice.kind` recomputes "is this a
  problem?" from the presence × assessment matrix.
- A developer reacts to delivered feedback, never to a private observation: authorization derives the
  recipient through `feedback`, and re-nag suppression follows its observation bindings to the recurrence
  locus.

## Evidence

- SARIF v2.1.0 — `result.kind` carries the pass/fail outcome; `result.level` is severity and is absent
  unless `kind = fail`; no rule-direction property exists.
  <https://docs.oasis-open.org/sarif/sarif/v2.1.0/sarif-v2.1.0.html>
- Single-point / criterion-referenced rubric — valence lives in the met/not-met of one criterion, never a
  separate good/bad attribute. <https://www.wested.org/blog/strengths-based-assessment-rubrics/> ·
  <https://www.cultofpedagogy.com/single-point-rubric/>
- Reaction targets the delivered artifact (xAPI Object as a StatementRef); audience ≠ actor (W3C Activity
  Streams `audience`/`to` distinct from `actor`).
  <https://github.com/adlnet/xAPI-Spec/blob/master/xAPI-Data.md> · <https://www.w3.org/TR/activitystreams-vocabulary/>
- Closed reaction enum maps to the recipience literature's validity vs next-step responses (Lui & Andrade
  2022; Carless & Boud 2018), not an open verb vocabulary.
  <https://www.frontiersin.org/journals/education/articles/10.3389/feduc.2022.751549/full> ·
  <https://eric.ed.gov/?id=EJ1193233>
- `developer` is a textbook 3NF transitive dependency; reference other aggregates by identity (Vernon, IDDD).
  <https://en.wikipedia.org/wiki/Third_normal_form> ·
  <https://www.informit.com/articles/article.aspx?p=2020371&seqNum=4>
- Keep a single time axis; derive the trajectory, do not store it; avoid bitemporal (Fowler).
  <https://martinfowler.com/articles/bitemporal-history.html>
