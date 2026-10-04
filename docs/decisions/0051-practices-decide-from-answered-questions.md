# ADR 0051: Practices decide outcomes from answered questions

**Status:** Proposed — on hold, not for 1.0. The direction after 1.0 is to keep the detected outcome
authoritative and run answered questions beside it as an advisory decision with a confidence
([#2445](https://github.com/hephaestus-build/Hephaestus/issues/2445)).
**Date:** 2026-10-03
**Amends:** [ADR 0050](0050-one-practice-standard-one-outcome.md), which kept the outcome a judgment the
model states from free-text criteria.

## Context

Under ADR 0050 the model read the criteria and stated `outcome` and `severity` itself. The criteria
carried the decision as prose (`## Occasion`, `## Judge`, `## Severity`), so the decision lived in a
place no reviewer, test or study could inspect. Two reviews that agreed on every fact could still
state different outcomes, and nothing recorded which fact decided. Measured against reference labels,
most disagreements were false `NOT_MET`: the model weighed a desirable behaviour against an
undeclared bar.

An expert judging a practice does not answer "met or not met" first either. They check a few facts —
is there an occasion, does the work do the thing, how bad is the gap — and the outcome follows from
those facts by rules they could write down.

## Decision drivers

- A recorded result must say which facts decided it, in words a developer and an author can check.
- The model answers what it can see; it never states an outcome.
- An unsettled fact must never become a claim about someone's work.
- An author edits the decision without editing a prompt.
- Experts in a study answer the same questions the review answers, so agreement can be measured per
  fact, not only per outcome.

## Considered options

1. Keep the stated outcome (ADR 0050). Rejected: the decision stays implicit and unmeasurable.
2. A model-authored rubric per review. Rejected: the decision moves, but it is still made by the
   model and changes from run to run.
3. Scores per criterion with a threshold. Rejected: a threshold has no meaning a developer can read,
   and calibrated confidences are not available from every provider.
4. Author-written yes/no questions and ordered rules, with the outcome derived on the server.
   Selected.

## Decision

A practice that Hephaestus reviews carries a `judgment`:

- **Questions** — 1–8 ordered yes/no questions, each with a `key`, a short `title` a YES affirms, the
  `question` as the reviewer reads it, and what `yes` and `no` mean for the work.
- **Rules** — 2–32 ordered rules, each with an `id`, conditions (`when`, question key to `YES` or
  `NO`), an `outcome`, a `severity` exactly for `NOT_MET`, and a `reason`: one sentence about the work
  that becomes the observation's headline. The first rule whose conditions match decides. The last
  rule has no conditions, so every combination of answers is decided, and every rule must be
  reachable. Both are checked when the practice is saved.

The review answers each question `YES`, `NO` or `UNDETERMINED`, each with `because` and the lines it
cites. A `NO` that rests on an absence carries the search that bounds it; an `UNDETERMINED` names
what would settle it. The model never sends an outcome, a severity or a rationale.

The server derives the outcome. An `UNDETERMINED` answer is resolved both ways (supervaluation):
when every resolution reaches the same outcome, that outcome stands at the least severe agreeing
severity, and a rule is named only when its conditions hold on the definite answers alone — a reason
one way of settling an open answer would make false is no fact about the work, so otherwise the
headline says only that the outcome holds however the open questions are settled. When they differ,
the outcome is `UNDETERMINED` and no rule decided. The observation records
the answers, the deciding rule and which answers decided it. Its evidence and warrant are derived
from the answers, so verification, anchoring and delivery are unchanged.

The criteria stay as guidance — the standard, the sources, what to cite, what other practices own —
and no longer decide anything. `INFO` severity is removed: a piece of feedback either asks for a
change or it does not.

Automated review requires a judgment. A practice with no automated review has none. A practice
withdrawn for insufficient evidence may keep its questions for the person who answers them; the
bundled catalogue gives every practice its questions.

## Migration

One forward pre-1.0 changeset, as ADR 0050 did. Every practice, current revision, instance
override and adopted base with automated review receives a holistic judgment that restates what its
criteria already asked: occasion, standard, major shortfall, critical shortfall. Earlier revisions and
earlier observations keep no answers, because none were given. `INFO` observations become `MINOR`.
The review-rule fingerprint becomes `v6:` and the curated digest `practice:v4:`. Bundled practices
ship written judgments. The rollback is a verified backup.

## Consequences

- Every surface changes together: catalogue and schema, persistence, the Pi runner and its tool,
  admission, the API and both generated clients, both practice editors, the observation views and Heph.
- The runner checks structure and says exactly what to fix; the server is authoritative for the
  outcome. A schema-valid answer can still be wrong, so evaluation measures agreement per question.
- Authors can now write a practice that never says `MET`. Saving checks that the rules are total and
  reachable, not that they are wise.

## Revisit trigger

Revisit when a provider supplies calibrated per-answer confidences, which could replace
supervaluation's all-or-nothing treatment of an open answer, or when evaluation shows that questions
cannot express a distinction a practice needs.

## Evidence

- Prototype on reference-labelled pull requests (praxis-bench, Qwen3.8-27B in the production Pi
  runner): stated outcomes agreed on 73 of 114 practice results, answered questions on 83, and false
  `NOT_MET` fell from 32 to 15.
- The full bundled catalogue on 34 pull requests of the held-out split (918 practice results, 197 of
  them reference `NOT_MET`; same model, one review session, every source the reference read staged):
  answering questions gave `NOT_MET` 34 times where the reference did not, against 50 for stated
  outcomes, but missed it 46 times against 33, so precision rose from 0.77 to 0.82 and recall fell from
  0.83 to 0.77. Agreement overall was 778 against 793. A review took a median 1037 s against 898 s, with
  about 28% more model calls and twice the refused recording calls. The reference is model-written, and
  reviewers who audited the questions saw errors from these cases, so the split is not unseen; the
  numbers show a direction, not a settled gain.
- A later build on the dev split (23 pull requests, 709 practice results, 124 reference `NOT_MET`,
  precompute leads staged for both setups): answered questions reported `NOT_MET` 13 times where the
  reference did not, against 47, and missed it 48 times against 29 (precision 0.85 against 0.67, recall
  0.61 against 0.77). Rewording three questions toward their criteria raised recall to 0.67 on the first
  12 of those pull requests, at precision 0.83. Fewer false lapses, more missed ones, every time: the
  trade-off is the reason this decision is held for an advisory role rather than replacing detection.
- [CheckEval](https://arxiv.org/abs/2403.18771) decomposes judgment into boolean checklist questions
  and reports higher agreement between evaluator models at comparable correlation with humans.
- [Supervaluationism](https://plato.stanford.edu/entries/vagueness/) treats a statement as settled only
  when it holds under every admissible way of making the vague part precise — the rule applied to an
  `UNDETERMINED` answer here.
- [Writing effective tools for AI agents](https://www.anthropic.com/engineering/writing-tools-for-agents)
  recommends tool errors that state specific, actionable corrections, which is how the runner refuses
  an answer.
- [OpenAI Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)
  constrains response shape, not correctness; the derivation is therefore never left to the model.
