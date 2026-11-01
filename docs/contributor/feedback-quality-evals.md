---
title: Feedback quality evaluation
description: The offline case format, checks, labels, and uncertainty reports for feedback and conversation answers.
page_type: reference
---

The offline harness scores selected claims against stored evidence.
It reports human labels and advisory judge ratings separately.
It makes no release decision.
The [labelling guide](./label-feedback-quality.md) gives the human review procedure.

## Case format

`scripts/feedback-eval/schema.json` defines the corpus, human labels, and judge results.
`scripts/feedback-eval/cases.json` contains three development regressions from test workspaces.
The cases preserve exact exported text, including redactions already applied during export.
The maintainer authorized these test-workspace exports for this evaluation.
Dataset privacy and export fidelity require human review.

| Field | Meaning |
| --- | --- |
| `sampleId` | An opaque identifier for one captured attempt. Identical outputs from repeated attempts retain separate IDs. |
| `group` | The work item shared by all variants, destinations, and repeated runs. A group cannot cross splits or sampling methods. |
| `split`, `sampling` | Development regression or a held-out random sample. Regression counts cannot estimate production quality. |
| `practice`, `provider`, `destination` | The practice, source integration, and output destination. Conversation answers use `conversation` as the practice group. |
| `capture.status` | Completeness of the retained evidence available to this evaluation. |
| `capture.producerStatus` | Recorded capture status when the output was produced. Unknown remains unknown. |
| `capture.limitations` | Missing material, redactions, truncation, and limits on interpretation. |
| `reviewState` | Reviewed, Withheld, or Not reviewed. These states remain separate. |
| `provenance` | Source record, revision, producer model, prompt digest, catalog revision, cost, and elapsed time. Unavailable values are null. |
| `evidence` | Exact text with a SHA-256 digest, completeness, revision, and diff side. |
| `output` | Exact produced text and its SHA-256 digest. |
| `claims` | Exact output spans, provisional verdicts, grounding labels, reasons, evidence references, and optional literal checks. |
| `claimCoverage` | Selected spans or an exhaustive decomposition. Selected claims do not establish whole-answer precision. |
| `expectedQuality` | Provisional relevance, repetition, usefulness, and withholding labels. Null means unscored. |

Evidence has four roles.
`seen` records material the producer read.
`reference` is an independent truth check.
`conversation` records what was said. It does not establish that those words were correct.
`compaction` records a fallible summary.

`seenByProducer` separately records whether the evidence was available to the producing request.
Unknown availability is null. Supported grounding requires evidence marked true.
The role alone does not establish availability after compaction.

Reference evidence does not prove that the producer read it.

Truth and grounding use separate fields.
The truth verdict is `true`, `false`, or `unsupported` against the retained evidence.
Here, `unsupported` means truth cannot be settled.
Grounding is `supported`, `unsupported`, or `unknown` against the producer's evidence.
A correct guess can be true and unsupported.

## Seed cases

| Case | Preserved material | Selected failure |
| --- | --- | --- |
| T4-9 | Stored explanation, verified citation, read receipts, admission result IDs, and recovered source bytes | An empty-body claim contradicted by `return 0;` in the cited catch |
| T4-4 | Exact answer, full selected tool results, tool calls, compaction summary, and independent source references | An invented successful creation test and an unsupported prior null-id claim |
| T2-2 | Exact follow-up, prior answer, user request, compaction summary, and untruncated stored tool results | Redundant commentary after an answer, without a new request |

T4-9's original candidate is unavailable, and its job folder was cleaned.
The full source file was recovered from the public reviewed commit.
Its raw SHA-256 matches the persisted read receipt exactly.
The case records the API, repository, path, commit, digest, and recovery date.
It keeps the recovered bytes separate from the original verified citation.
The surviving original job evidence remains partial.

T2-2 scores the follow-up, not the truth of every claim in the earlier answer.
The cases include true claims as controls.
Their provisional labels are not human semantic acceptance.

## Deterministic checks

The loader validates schemas, content digests, claim spans, references, and split boundaries.
It rejects stale ratings, repeated IDs, and incomplete claim ratings.

Literal checks operate on a manually annotated claim and an exact evidence region.
A `literal-absence` check reports `annotated-text-present` when the selected bytes occur in that region.
It abstains otherwise.
A `literal-quote` check reports an exact match or abstains.
Neither check establishes general semantic correctness.
Text inside a comment or string can also match.

An incorrect annotation can invalidate the conclusion. Human review must check the annotation.

Matching preserves exact text, revision, and diff side.
The harness does not normalize whitespace, Unicode, or diff prefixes.
A spelling difference can cause abstention. It cannot prove an absence claim.
Checks do not parse arbitrary prose or infer API behavior from an identifier.

## Offline commands

The scorer needs the repository's pinned Node version and installed dependencies.
It reads local files and sends no provider request.

```bash
node scripts/feedback-eval.ts
node scripts/feedback-eval.ts --requests > .context/eval-requests.json
node scripts/feedback-eval.ts --corpus /private/eval/cases.json \
  --labels /private/eval/humans.json --judge /private/eval/judge.json
```

The request export is suitable for human labellers and a rubric judge.
Content digests prove self-consistency, not the source or export fidelity.
The maintainer checks the retained export digest against the original export.

The request export excludes provisional labels, rationales, checks, practice names, case names, and producer metadata.
Its case hash covers only the blinded request, not hidden labels.
The judge result also binds to the rubric digest.
An evidence or output change invalidates earlier ratings.
A label correction does not change the blinded request hash.

Live execution belongs to the release or bench lane on staging after merge.
It retains raw requests and responses and converts responses to the schema's judge-result format.
The lane records the judge model and run identifier.
No live provider credentials are needed for offline replay.
The existing [criteria and voice tests](./testing.mdx#live-external-service-tests) remain separate.

## Reports and uncertainty

Reports separate practice, destination, split, and sampling method.
They show selected claim counts, missing or disputed labels, failed captures, partial captures, and unresolved claim IDs.
Producer capture failures stay outside developer-quality denominators.
Withheld and Not reviewed remain separate counts.
Quality measures include relevance, repetition, usefulness, and correct withholding where labels exist.

Quality rates count true labels. A high repetition rate is adverse.
Missing or disputed quality labels remain explicit counts.

Known-problem recall is not implemented in this bounded harness.

Regression results are descriptive counts and rates.
Their confidence intervals are null because hand-picked failures do not represent a population.
Selected claims cannot establish a whole-output rate.

Human ratings on exhaustive, randomly sampled held-out cases can have Wilson 95% confidence intervals.
These intervals describe work items with any false claim or any unsupported claim across their recorded attempts.
Each work item is one binary trial, regardless of its number of claims or repeated outputs.
These are work-item error rates, not claim precision.
Selected-claim rates remain descriptive and have no confidence interval.
The [NIST reference](https://www.itl.nist.gov/div898/handbook/prc/section2/prc241.htm) gives the Wilson formula.

The sampling flag and decomposition coverage are owner attestations, not verified facts.
The owner retains the sampling frame, draw method, seed, and date outside the repository.
An independently retained export-manifest digest establishes the source to audit.
The interval assumes independent work items and complete agreed human labels.
Rates are conditional on successful captures.

Use one arm per report and the same repeat count for each work item.
Unknown or mixed producer model and prompt digests disable intervals.
Unequal attempt counts per work item also disable intervals.

Missing or disputed labels disable intervals for the stratum.
Unsettled truth or unknown grounding cannot establish that a work item has no error.

Each interval is marginal. It is not a simultaneous guarantee across practices or destinations.
Regression, judge-only, and provisional results have no inferential interval.
[Statistical evaluation guidance](https://arxiv.org/abs/2411.00640) explains dependence and paired comparisons.

Original human labels remain intact after adjudication.
Reports show pairwise raw agreement and Cohen's kappa before adjudication.
They also show judge-human confusion counts and agreement on the resolved overlap.
For quality agreement, null is an explicit abstention category.
A null label paired with a value counts as disagreement.

A null kappa can mean no overlap or constant labels. It does not mean perfect agreement.
[Artstein and Poesio](https://aclanthology.org/J08-4004/) explain chance-corrected agreement and its limits.

Every report says `releaseDecision: not-assessed` and `judgeUse: advisory-only`.
There is no precision-drop gate or paired release comparison in this harness.
The three seed cases cannot close the broader correctness issues.
The [provenance contract](./evaluation-provenance.md) owns the interpretation of review and delivery records.

## Composition set

The composition set measures the review on the work that the composer writes from admitted results.
`scripts/feedback-eval/compositions.json` holds 60 synthetic pull request and merge request cases.
The cases use fictional repositories and people, and they hold no personal data.
The [composition label rubric](./composition-label-rubric.md) owns the labels and the measures.

Each case holds the captured work, the cited files, the admitted Not met results and their problems, and the recorded limits.
It also holds what Hephaestus and reviewers already said on the work.
Each case gives the expected decision for every Not met result, a reference composition, and labelled alternatives.
The criteria come from the shipped catalog, so a criteria change applies to the set without an edit.

`CompositionEvalTest` turns each case into a scenario in the layout of the golden pull-request scenario.
Then it renders the case through main's composer: the system prompt, the opening turn and the `report_review` tool.
Main's check is `ReviewOnWork.read` and then the text policy that preparation applies to links, mentions, HTML, images and hidden characters.
It does not read what the words claim.
The test fails when main's check refuses a reference composition.

The loader fails when a label is inconsistent. A good label must have no code. Words over a length target or with a link, mention or HTML must carry that code.

The cases are hand-authored, not a random sample.
The report gives descriptive counts and rates, with no confidence interval.
The set is public, so a prompt change can overfit it. Report a prompt change against the set before and after.

### Run the composition set

These commands run from the repository root.
The first one writes the model requests and the check of every labelled composition:

```bash
(cd server && COMPOSITION_EVAL_REQUESTS=/tmp/ev/requests.json COMPOSITION_EVAL_GATE=/tmp/ev/gate.json \
  ./gradlew :application:test --tests CompositionEvalTest)
```

A live run composes every case with the model that the three `HEPHAESTUS_LIVE_LLM_*` variables name.
It uses main's `ModelSession` and the `OPENAI_COMPLETIONS` protocol.
The session follows the composer's review session: its call bound, one finish reminder, the nudge, and its answer to a refused review:

```bash
(cd server && COMPOSITION_EVAL_CHECKED=/tmp/ev/in-house.json \
  ./gradlew :application:liveTest --tests CompositionEvalLiveTest)
```

`COMPOSITION_EVAL_REASONING_EFFORT` sets the reasoning effort. The default is `MEDIUM`.
`COMPOSITION_EVAL_MAX_OUTPUT_TOKENS` sets the output allowance. The default is 32,768.
`COMPOSITION_EVAL_CASES` selects case IDs, separated by commas.

The `liveTest` task stops after 20 minutes. The run writes its file after each case, and a rerun composes only the cases that the file does not hold yet.
A rerun refuses a file from another model, other settings or other rendered requests.
The run sends requests directly to the endpoint, without the proxy that fences and bills them in production.

A run from another runner writes one `report_review` argument object for each case into a file such as `outputs.json`.
This command applies main's check to that file and writes the checked run:

```bash
(cd server && COMPOSITION_EVAL_OUTPUTS=/tmp/ev/outputs.json COMPOSITION_EVAL_CHECKED=/tmp/ev/outputs-checked.json \
  ./gradlew :application:test --tests CompositionEvalTest)
```

The report puts the runs side by side:

```bash
node scripts/feedback-eval.ts --compositions scripts/feedback-eval/compositions.json --gate /tmp/ev/gate.json \
  --run in-house=/tmp/ev/in-house.json --run-labels in-house=/tmp/ev/in-house-labels.json
```

Each `--run` and `--run-labels` pair has the form `name=file`.
The labels file holds one human label for each composed review, with the rubric codes.
The good rate needs a label for every composed review. With fewer labels, the report lists the unlabelled cases and gives no rate.
Without a labels file, the report gives only the raise decisions, main's acceptance and the mechanical codes.

## Practice outcome cases

`server/application/src/test/resources/practices/criteria-cases.json` holds 20 labelled outcome cases for each of ten practices.
Their IDs start with `ev-`.
The [criteria tests](./testing.mdx#live-external-service-tests) run them with a live model:

```bash
(cd server && ./gradlew :application:liveTest \
  -PcriteriaCases="$(node -e 'const c=require("./application/src/test/resources/practices/criteria-cases.json");console.log(c.filter(x=>x.id.startsWith("ev-")).map(x=>x.id).join(","))')")
```

A failed case is a mismatch with the label, not a harness failure.
The JUnit report gives the mismatches.
