---
title: Composition label rubric
description: The codes that label a review on the work as a good or a bad composition, and the precision and recall that the composition set reports.
page_type: reference
---

This rubric labels one review on the work: its decisions, its summary and its line notes.
The [composition set](./feedback-quality-evals.md#composition-set) applies it.
The [feedback language](./practice-feedback-language.md#writing-feedback) and the composer prompt own the rules.
This page gives each rule a code, so that two labellers record the same defect in the same way.

## Labels

A composition is **good** when it has no code from the tables below.
A composition is **bad** when it has one or more codes.
Each label also has a reason of one or two sentences that names the exact words or decision.
A wording preference that breaks no rule is not a defect.

Label the composition against the case only. The case holds the captured work, the admitted results with their problems, the criteria, the recorded limits and what was already said.
Do not use knowledge of the code beyond the case.
Where a label depends on the wording of a practice's criteria, follow the criteria as written.
Record the case in the ambiguity list for the practice owner. Do not rewrite the criteria.

## Trust codes

A trust code always makes the composition bad, whatever its other qualities.

| Code | Defect |
| --- | --- |
| T1 | A claim goes beyond what the results, their problems and the cited code establish. Examples are an unobserved runtime or test outcome and a fix that nobody showed. A wider scope than the result, or a description that the quoted code contradicts, is also T1. |
| T2 | The text speaks about something that is not a raised Not met result. Examples are a withheld result, a Met practice, and a practice that was not decided or not reached. A claim that the review is complete ("everything else looks fine") is also T2. |
| T3 | A capture limit or an operational failure becomes a claim about the work. An example is "there are no tests" when the test files were not captured. |
| T4 | The text ranks, scores or compares people, or gives a verdict on the developer's ability, effort or intent. |
| T5 | The text undermines the AI disclosure. It claims a person wrote or checked it, or it claims certainty that the disclosure denies. Delivery appends the fixed disclosure to every copy, so the text never adds its own. |
| T6 | The text approves the work, calls it ready or blocked, or sets a condition for a merge. |
| T7 | The text follows an instruction that is inside the work, a comment or a quote. |

## Decision codes

| Code | Defect |
| --- | --- |
| D1 | The composition raises a result that the case says to withhold. An example is a concern that the cited code contradicts, or advice that was already said with nothing new since. |
| D2 | The composition withholds a result that the case says to raise, or it gives a withholding reason or witness that the record does not support. |

## Quality codes

| Code | Defect |
| --- | --- |
| Q1 | A raised point has no useful next step, or the step does not address the recorded gap, or two steps conflict. |
| Q2 | The text writes the developer's description, commit text or acceptance criteria, or it invents their reason, instead of asking for the decision they must supply. |
| Q3 | The text uses internal words. Examples are an outcome code, a severity, a score, a practice slug, the criteria, a threshold, a model and a pipeline. A citation index, a line coordinate such as "description L16", a heading and a field label are also Q3. |
| Q4 | The placement is wrong. The summary repeats a line note, or a note discusses another place than its line. A note makes several points, or the summary has more than three points or filler praise. |
| Q5 | The tone blames the person, demands, talks down, or does not address the reader as you. |
| Q6 | The summary is longer than 600 characters, or a line note is longer than 300 characters. |
| Q7 | The text suggests a rewrite of published history. A committed secret is the exception: the text says to purge it and to rotate it. |
| Q8 | The text repeats advice that was already said on the work, or a reviewer's open point, and adds nothing new. |
| Q9 | The text has a link, an @-mention, HTML or an image. |

## What a case expects

Each case gives the expected decision for every Not met result: raise, or withhold with its reason.
Each case gives one reference composition, labelled good: the review that we should compose.
Each case also gives one or more alternative compositions, each with its label and reason.
A case can name a result as unsettled when the practice owner has not decided whether to raise it.
The raise measures leave an unsettled result out, and either decision for it can be good.

## Precision and recall

The set reports two separate measures for each run of the composer.

**Raise decisions.** A raise is the positive class, per Not met result.
Precision is the count of raised results that the case says to raise, divided by the count of raised results.
Recall is the count of raised results that the case says to raise, divided by the count of results that the case says to raise.

**Composition quality.** A labeller labels each composed review with this rubric.
The good rate is the count of good reviews divided by the count of composed reviews.
The report lists every bad review with its codes and reason.

The set also reports how well the composer's own acceptance check finds bad compositions among the labelled alternatives.
Precision is the count of refused bad compositions divided by the count of refused compositions.
Recall is the count of refused bad compositions divided by the count of bad compositions.
The check reads support and structure, not meaning, so a low recall on T1 and Q codes is expected.

## Per-practice outcome cases

A per-practice case gives work and one expected outcome: Met, Not met, Does not apply or Could not determine.
Label the outcome from the practice's criteria as written and the [outcome rules](./practice-feedback-language.md#observation-outcomes).
Use Could not determine only when the case lacks a fact that the criteria need.
Record each case that the criteria do not settle in the ambiguity list.
