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
Then it renders the case through main's composer: the system prompt, the opening turn and the `check_package` tool.
Main's check is `ReviewOnWork.read` and then the text policy that preparation applies to links, mentions, HTML, images and hidden characters.
It does not read what the words claim.
The test fails when main's check refuses a reference composition.

The loader fails when a label is inconsistent. A good label must have no code. Words over a length target or with a link, mention or HTML must carry that code.

The cases are hand-authored, not a random sample.
The composition report gives descriptive counts and rates. The release report also gives binomial reference intervals, with the limits below.
The set is public, so a prompt change can overfit it. Report a prompt change against the set before and after.

### Run the composition set

These commands run from the repository root.
The first one writes the model requests and the check of every labelled composition:

```bash
(cd server && COMPOSITION_EVAL_REQUESTS=/tmp/ev/requests.json COMPOSITION_EVAL_GATE=/tmp/ev/gate.json \
  ./gradlew :application:test --tests CompositionEvalTest)
```

A live run composes every case with the model that the three `HEPHAESTUS_LIVE_LLM_*` variables name.
It runs the composer that production runs: the Node `review_compose` loop, over the real composer sessions and the `OPENAI_COMPLETIONS` protocol.
The sessions render the prompts, check each draft and accept its publication, as in production.
A case is accepted when the loop publishes a draft that the server checked.
Its review is that draft, or else the last draft that the loop checked.
The run stops at preparation, before approval and delivery:

```bash
(cd server && COMPOSITION_EVAL_CHECKED=/tmp/ev/in-house.json \
  COMPOSITION_EVAL_CASES=gh-a-01-menu-view-does-its-own-networking \
  ./gradlew :application:liveTest --tests CompositionEvalLiveTest)
```

`COMPOSITION_EVAL_REASONING_EFFORT` sets the reasoning effort. The default is `MEDIUM`.
`COMPOSITION_EVAL_MAX_OUTPUT_TOKENS` sets the output allowance. The default is 32,768.
`COMPOSITION_EVAL_CASES` selects case IDs, separated by commas.

The `liveTest` task stops after 20 minutes. Select a bounded set for a direct invocation.
The release script runs the complete panel in separate invocations. Each invocation retains the same measurement file.
A rerun composes only the selected cases that the file does not hold yet.

A rerun refuses a file from another model, other settings or other rendered requests.
The run sends requests directly to the endpoint, without the proxy that fences and bills them in production.
A private case opens only its own destination, and the review on the work counts as decided.
Its composition request comes from the item's copy forms, as in production.

A run from another runner writes one `check_package` argument object for each case into a file such as `outputs.json`.
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
The labels file holds one label for each composed review, with the rubric codes. State whether the labels are AI-drafted or human.
The good rate needs a label for every composed review. With fewer labels, the report lists the unlabelled cases and gives no rate.
Without a labels file, the report gives only the raise decisions, main's acceptance and the mechanical codes.

## Feedback eval gate

The feedback eval gate stops a change that weakens what the prompts tell the model.
Its deterministic checks gate each pull request that touches what they measure.
That is a prompt, the writing guide, the practice catalog or its scripts, the practice-script corpus, the composer, or the gate itself.
[Path filters](./ci-cd.mdx#path-filters) list the paths.
`scripts/feedback-eval/gate.json` holds its fixed case set, its prompt clauses and its floors.

### Gate checks without a model

These checks send no model request. Each check must pass exactly.
They detect edits. They do not judge what an edit means: only the [model panel](#model-panel) composes with a model.

| Check | Fails when |
| --- | --- |
| G1 | A pinned case changed or is missing. Or `gate.json` changed since the base commit, and its `version` did not increase. |
| G2 | The composition set or the claim corpus does not parse. |
| G3 | A quote check does not match, or an absence check finds its text and the claim label is not `false`. |
| G4 | A prompt changed, or a new prompt file has no pin. Or a prompt no longer contains a clause that `gate.json` names. |
| G5 | An automated practice has no Applicability section in its criteria. An Applies when gate or a scan section does not replace it. Or a practice's criteria or Applies when changed. |
| G6 | A bundled practice script gives other leads than its snapshot in `docker/agents/practice-scripts/corpus/snapshots/`. |
| G7 | The pinned cases times the calls for each case permit more model calls than the panel's ceiling. |

A pin is a SHA-256 of one of these:

- A case, as the fixture file holds it.
- A whole prompt file under `agent/` or `agent/mentor/`, or the writing guide's Writing feedback section.
- A practice's criteria and Applies when.

A failure prints the new digest.

Each G4 clause carries one occasion criterion (O1–O8) or one evidence-bound criterion (E1–E15).
The criterion ID is in `gate.json` beside the clause.

A text check cannot tell a rule from its reversal, and a new paragraph can override a rule that is still present.
Thus, G4 pins each whole prompt. Any edit to a prompt fails until the pull request pins it again.
The clause check names a rule that went missing. The panel and the review of the pull request judge what an edit means.

G5 keeps a list of known gaps: automated practices without an Applicability section.
The check also fails when a listed practice gets one. Then remove it from the list.

G6 runs `docker/agents/practice-scripts/corpus.test.ts`. Each bundled script runs over the authored corpus, without a model.
To change what a script sends on purpose, run that test with `--test-update-snapshots` and commit the new snapshot.

To pin a changed case, prompt or practice, put the new digest in `gate.json` and increase `version` in the same pull request.
The review of that pull request is the review of the change.

### Model panel

The model panel composes 20 fixed public cases with the production composer, one run each.
The panel holds every case with an expected withhold, the capture-limit case and two instruction cases.
It scores the output against the case labels. It does not use a judge model.
Only the panel measures how the composer behaves. The deterministic checks do not.

| Measure | Floor |
| --- | --- |
| P1. Cases composed and accepted by main's check | 18 of 20 |
| P2. Cases with no mechanical code (Q6, Q9) | 18 of 20 |
| P3. Raise recall | 11 of 13 |
| P4. Withhold decisions correct | 8 of 17 |

Each floor is the one-sided binomial quantile at the rate of the rc.5 measurement, at a false-alarm level of 1.25 %.
The four floors share a 5 % budget.
A floor finds only a large regression. The [release eval](#release-feedback-eval) remains the quality bar.

Each case is one `CompositionEvalLiveTest` invocation, with 16,384 output tokens and medium reasoning effort.
The composer loop sends at most 12 model calls for each case, and G7 keeps the pinned cases within 240 calls.
The run also counts the HTTP requests that each case recorded. The eval transport sends one request for each model call and never probes a second path. It stops before a case that could pass 240 calls, and after a case that passed its bound of 12.
Such a run ends as "ceiling reached", not as a pass.

The eval composer sends a failed model request once: a provider failure ends that case and is recorded as its failure.
The job stops after 40 minutes.

### When the panel runs

The panel does not run for each pull request. The `CI / Feedback eval panel` workflow runs it:

- Each night on `main`.
- On a manual run.
- On a pull request with the `eval:run` label, when the label is added and on each later push.

The panel runs only with a configured model provider.
[Feedback eval panel provider](./ci-cd.mdx#feedback-eval-panel-provider) lists the settings and what happens without them.

### A skipped panel

With a provider, the panel is skipped when the endpoint gives no composition for a panel case.
An endpoint failure is never retried.
A skip is not a pass: the panel job fails and names the reason.

With a provider, a night can fall below a floor, be skipped or not finish.
Each such night comments on one tracking issue, "The nightly feedback eval panel is not green".
The workflow opens the issue if it is not open.

The comment links the run, the kept result and the commits since the last night whose panel measured and passed.
[Feedback eval panel provider](./ci-cd.mdx#feedback-eval-panel-provider) says how the workflow keeps that night.
The next night that measures and passes closes the issue with a link to its run.

A pull request run and a manual run report in their job summary only.
If the report job itself fails, or the night is cancelled after its panel started, `CI / Scheduled failures` writes to the same issue.
Both call one script, `scripts/feedback-eval-night.ts`, so the fallback gives the same commit range and result link.

Each comment carries a marker of its run attempt. A replay of an attempt that a panel issue already holds, open or closed, adds nothing.
The check reads only issues updated since the attempt started. If it fails, the report still goes out and says that the duplicate check could not complete.

The cicd job keeps `feedback-eval-gate.json` as the `feedback-eval-gate` artifact for 14 days. The panel keeps it as `feedback-eval-panel`.
The file holds each check, each measure, the model name and the SHA-256 of the endpoint. It never holds the endpoint or the key.

The gate runs locally without a model.
With `--base`, it also compares `gate.json` with the base commit, as pull request CI does:

```bash
node scripts/feedback-eval-gate.ts --base "$(git merge-base origin/main HEAD)"
```

## Composition replay cases

A replay case fixes what the composer loop admits for one sealed review and one sequence of model answers.
It proves that a changed loop composes as the current one does.
`server/application/src/test/resources/agent/composition-replay/` holds the cases. Each case names a golden scenario as its sealed basis.
A case file holds no Java, so a loop in another language can replay it against the same harness.

A case lists the model's answers in order:

- A scripted answer is a tool call, a text or a provider status. In tool arguments, `${draftId}` stands for the draft ID that the last check returned.
- A recorded answer is a live model's message: its role, text and tool calls. A failed call is recorded by its status.
  The recorder keeps none of the endpoint's own fields, and it numbers the tool call IDs in order.

The expected outcome is what the server admitted:

- Every package that reached preparation.
- The stops that the review on the work recorded.
- The cause that handed the attempt on.
- What each model call added to its conversation. A long turn is recorded by its SHA-256 and length, because the golden scenarios hold the text.

`ComposerReplayTest` replays every case and compares the outcome exactly. It runs in the unit tier.
The harness runs the real composer sessions over an in-memory result admission.
Thus, a case proves the loop and the sessions, but not how admission stores what it prepares.
With `UPDATE_COMPOSITION_REPLAYS=1`, it rewrites each expected outcome. Review the diff before you commit it.

`ComposerReplayLiveTest` measures the noise floor of a live model and records cases from it.
It composes each basis `COMPOSITION_REPLAY_RUNS` times (default 3, at least 2), and it writes `COMPOSITION_REPLAY_REPORT`.
The report gives two shares of run pairs that decided alike:

- A run pair agrees when both runs decide every destination alike and hand the attempt on with the same cause.
- A destination pair agrees when both runs prepare or withhold the same pieces there.
  The pieces must have the same reasons, results, notes and summary presence.
  A pair in which neither run produced the destination does not count.

The report does not compare words.

The first run of each basis must replay exactly from its answers. The test writes the report before it asserts this.
With `COMPOSITION_REPLAY_RECORD=1`, it writes that run as the case `<scenario>-recorded`.
`COMPOSITION_REPLAY_BASES` selects the scripted cases whose bases run, separated by commas.

## Practice outcome cases

`server/application/src/test/resources/practices/criteria-cases.json` holds 20 labelled outcome cases for each of ten practices.
Their IDs start with `ev-`.
The [criteria tests](./testing.mdx#live-external-service-tests) run them with a live model:

```bash
(cd server && ./gradlew :application:liveTest \
  -PcriteriaCases="$(node -e 'const c=require("./application/src/test/resources/practices/criteria-cases.json");console.log(c.filter(x=>x.id.startsWith("ev-")).map(x=>x.id).join(","))')")
```

The JUnit report includes the outcome mismatches and execution failure details.

## Release feedback eval

Run `node scripts/release-feedback-eval.ts --output <new-directory>` on the validation host through the existing validation command.
Load the three live-model variables inside that remote command from the protected credential file.
Keep the credentials on that host.

Use an output directory outside the checkout or in its ignored scratch directory.
The script runs each case of `CompositionEvalLiveTest` in a separate bounded invocation on the committed checkout.
It writes `compositions.json` and `results.json` in the output directory.
It refuses a dirty checkout, a partial case selection and a run from another commit.
A skipped live test cannot supply a results file.

Draft labels for every composed review against the [rubric](./composition-label-rubric.md).
Use judgment about the captured evidence. A recorded interpretation is not proof of the facts it claims.
Keep the full outputs for inspection.
The labels file adds `source: "ai-drafted"` and `runSha256`, the SHA-256 of `compositions.json`, beside `version` and `labels`.
These are AI-drafted labels, not independent human judgments.

Run the same script with `--output <same-directory> --labels <labels-file> --score-only` to score those outputs without another model call.
The results file records the source commit, model settings, output and label digests, rates, reference intervals and every finding.
The script exits with failure for incomplete evidence or any labelled defect.
Do not remove a defect label to make the script succeed.
A correction needs a new committed run and new labels bound to its outputs.

The release owner decides what the findings permit. `no-findings-on-panel` is not release approval.

The intervals use Wilson's 95% binomial formula on the fixed synthetic case panel.
They are reference intervals, not confidence bounds for deployed feedback: the cases were selected, can be correlated and have AI-drafted labels.
Raise precision and recall remain descriptive per-result measures. Their results can share one work item.
The case-level decision rate requires the exact expected disposition, including a supported withholding reason.

The public panel measures composition on GitHub and GitLab work.
The private panel reuses four synthetic source cases on the practice page and in conversation notes. Each case has one or two supporting work items.
It covers supported concerns, contradicted interpretations, capture limits and instructions in the work.
Each private destination runs in isolation, without a prepared public review, through its own composition and acceptance check.
Public withholding does not decide a private destination.

The two-occurrence variants repeat the synthetic concern on another synthetic work item. They do not establish a real developer pattern.
The labels must cover all 16 private outputs as well as all 60 public outputs.

The occasion panel runs six controlled assessment contrasts through the practice runner, with the shipped criteria.
They cover a permission scaffold, protected access with and without its usage key, and merged outcome accounts with complete and incomplete reporting.
The unmerged control tests the occasion itself. The expectations are AI-drafted from the current criteria.
The script records each actual outcome and severity before the test checks them. Missing cases remain failures.

Completion and assessment accuracy have separate rates. An execution failure supplies no assessment to grade.

The panel stops at validated drafts. It does not measure publication, final conversation answers in the app or Slack, or provider delivery.
The results file lists these gaps. Slack and Outline assessment adapters are outside this panel.
Do not use this report to claim quality for an unmeasured destination or adapter.

### Evidence provenance

The release script writes `execution.json` after every case.
It records the commit, requested model settings, endpoint digest and each test's exit status.
A scoring pass requires this record and checks it against the composition and occasion files.
Use a new output directory when the commit, model or endpoint changes.
Composition outputs record the model identities returned by the endpoint. Occasion rows record the model identity in the runner usage report.

Run labels name an independent labeller in `labeller.model` and bind `labeller.rubricSha256` to the current composition label rubric.
The release script refuses labels from the composing model and labels from another rubric revision.
This provenance records a judgment process. It does not turn drafted labels into human labels.
Private cases also have expected delivery actions, so an accepted package that withholds every useful piece does not pass.
