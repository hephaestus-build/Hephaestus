---
title: Label feedback quality
description: Label claim truth, grounding, and answer quality without seeing the producing model or provisional answers.
page_type: how-to
---

Use this procedure to label feedback and conversation answers against captured evidence.
The [format reference](./feedback-quality-evals.md) defines the files and report limits.

## Before you start

The evaluation owner provides a blinded request export, its rubric, and an opaque reviewer ID for each labeller.
Use two independent labellers before adjudication.
Keep the held-out corpus, labels, and model-arm key outside the public repository.
Commit only synthetic or authorized test-workspace regressions.
A labeller who sees held-out outputs must not tune prompts before the comparison ends.

The release evaluation needs 60 composition cases and 20 cases for each of ten high-volume course practices.
Select the practice sample randomly before inspecting its outputs.
Keep hand-picked failures and controls separate.
Split by repository or work item before prompt changes.
Include correct, inapplicable, already-addressed, stale, incomplete, injection, and repetition cases.
Use the same cases and repeat counts for both arms.

## Prepare the blinded set

1. Export the cases with `node scripts/feedback-eval.ts --corpus /private/eval/cases.json --requests`.
2. Assign neutral reviewer IDs. Keep model names and the arm key away from both labellers.
3. Shuffle case and arm order independently for each labeller. Record the order privately.
4. Give each labeller the same evidence and rubric. Hide provisional labels and other ratings.
5. Include frozen criteria as evidence for practice relevance or withholding judgments. Do not rewrite a criterion while labelling.

For a new exhaustive case, split every independently verifiable assertion into a claim.
Separate descriptions, causes, absence claims, applicability judgments, and proposed remedies.
Keep qualifications with the assertion they limit.
Do not select only suspicious claims.
If decomposition is incomplete, mark `claimCoverage` as `selected`.
A selected-claim rate is not whole-output precision.

## Label each claim

1. Read the current request, output, evidence roles, and capture limits.
2. Copy the claim ID and exact span. Check that the span retains its scope and qualifications.
3. Assign truth: `true`, `false`, or `unsupported`. Use `unsupported` when retained evidence cannot settle truth.
4. Assign grounding: `supported`, `unsupported`, or `unknown` against what the producer could read.
5. Write a short reason and exact evidence IDs. A true or false verdict requires evidence references.
6. Label relevance, repetition, usefulness, and correct withholding. Use null when evidence cannot settle a quality judgment.
7. Save a complete rating for the case under your reviewer ID. Do not read the other labeller's answers first.

A valid citation does not establish the explanation built on it.
A filename does not establish a test body.
A service call does not establish the called API's contract or its HTTP mapping.
A reference-only fact can establish truth without establishing producer grounding.
A summary or an earlier answer does not independently confirm a fact.

For T4-9, judge the empty-body assertion separately from whether the practice outcome was appropriate.
Different practices can legitimately assess different properties of the same catch.
For T2-2, the self-reference can be true while the output is repetitive and unhelpful.
Do not turn a repetition defect into a false factual label.

A failed capture is a runtime failure, not evidence of a developer fault.
Withheld feedback is not missing evidence and is not a met practice.
An absent delivery record does not prove that nothing reached the developer.

## Resolve disagreements

1. Store both original ratings in `labels`. Keep their case hashes and reviewer IDs.
2. Read the report's missing or disputed counts and pairwise agreement before resolving disagreements.
3. Compare reasons against exact evidence. Preserve `unsupported` or `unknown` where evidence cannot settle the question.
4. Add the agreed claims to `adjudications`, with both reviewer IDs and reasons. Preserve the original labels.
5. If agreement is still impossible, leave the claim unresolved. Report it as missing. Do not silently drop it from acceptance evidence.

The human file has `version: 1`, `labels`, and `adjudications`.
Each label has `reviewer`, `caseHash`, `claims`, and `quality`.
Each adjudication replaces `reviewer` with a `reviewers` list.
An adjudication can include only the claims resolved so far.
Omitted claims retain independent consensus or remain missing.
Null quality keys in an adjudication retain the independent consensus for those keys.

The [JSON schema](https://github.com/hephaestus-build/Hephaestus/blob/main/scripts/feedback-eval/schema.json) gives the exact fields.
The import requires the adjudicators' original independent labels.
It cannot verify the identity or independence of the people who created a file.

## Check that it worked

```bash
node scripts/feedback-eval.ts --corpus /private/eval/cases.json --labels /private/eval/humans.json
```

The report must show both reviewer IDs and the expected unresolved count.
Check the original agreement and the resolved counts separately.
Read a sample of source spans and reasons before accepting the label set.
A missing or disputed label must not disappear from the report.

After labelling, score each model arm as its own corpus.
Include only that corpus's labels and judge results.

The release owner predeclares the paired comparison, sampling plan, repeat count, and spend cap before live runs.
Use the current default and one cheaper model arm, with critique disabled.
Judge ratings stay advisory and require calibration against the human labels.
Use a different judge model family where available, shuffle candidates, and retain raw judge responses for inspection.
A schema-valid response can still follow an injected instruction or make a false inference.
The offline harness does not approve a release.
