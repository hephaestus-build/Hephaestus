# ADR 0142: A maintainer signs a manual release check, and an offline simulation checks contact timing

**Status:** Accepted
**Date:** 2026-11-24
**Authors:** Felix T.J. Dietrich

**Builds on:** [ADR 0092](0092-ci-builds-each-image-once-a-release-signs-one-lock-and-hosts-pull-it.md) (the release is one packaged artifact)

**Relates to:**

- [ADR 0149](0149-a-delivery-experiment-draws-only-unprompted-contact-per-person-and-per-decision.md) (the run fingerprint)
- ADR 0139 (proposed in #4865, the contact policy and delivery decisions)
- ADR 0121 (proposed in #4862, isolation tests replace a model-based disclosure check)
- [ADR 0141](0141-research-datasets-build-on-the-research-module.md) (reference traces)
- [ADR 0080](0080-provider-events-go-to-a-postgresql-inbox.md) (one server role receives provider events into the PostgreSQL inbox)
- [Feedback quality evaluation](../contributor/feedback-quality-evals.mdx#decision-on-the-composition-set) (the composer decision already runs on Ichnaea)

## Context

The conversation agent talks with people and writes the messages that Hephaestus starts.
The contact policy decides in code when Hephaestus reaches out (ADR 0139, proposed in #4865).
Quality has two parts. One part is what the agent says. The other part is when and how often Hephaestus speaks.

Continuous online evaluation needs traffic, a paid rating budget and measures on people.
Hephaestus has no such budget, and it never measures a person.
Simulated users are too cooperative to judge the quality of feedback, but code can check timing exactly.

The real constraint is evaluation that a maintainer can run by hand before a release, at almost no model cost.

## Decision drivers

- Nothing runs continuously, and nothing needs a paid rating budget.
- Nothing measures a person. An online signal stays at instance level, over at least 20 people.
- An exact detector, not a judge score, is a hard gate. A hard gate allows zero breaches.
- A signed check can be repeated with the same models and settings.
- The release check runs on the artifact that ships.
- One evaluation framework. Ichnaea already holds the contracts, the paired decision and the reports. Hephaestus builds only what is specific to Hephaestus.

## Considered options

1. **Continuous online evaluation** with sampled conversations and paid raters. It needs a budget, and it reads people's conversations as a routine.
2. **Judge scores as release gates.** A judge's score drifts with the judge model. A gate on it fails for reasons that are not defects.
3. **A/B tests or bandits on people in the product.** An experiment on people is research and needs its own consent. [ADR 0149](0149-a-delivery-experiment-draws-only-unprompted-contact-per-person-and-per-decision.md) allows only delivery experiments, under enrollment.
4. **A simulation that also grades the quality of feedback.** Simulated people are too cooperative, and traces with structure only cannot show a content failure.
5. **Scenario replay as a release gate.** One slice of one project is not a sample, and frozen reactions cannot answer what changed. A gate on it would fail on differences of judgment.
6. **Two rubrics, one for release cases and one for replay.** Two sets of criteria for one question drift apart.
7. **The scenario harness inside Ichnaea.** The harness encodes Hephaestus's providers, its authority rules, its clock and its packaged server. Ichnaea makes no network call of its own. It keeps scoring on the suite side, so the harness belongs to the consumer.
8. **An in-process harness with a prototype subject.** It is fast, but it claims nothing about the server that ships. A subject that reports its own reads cannot be judged by them.
9. **Four layers with scenario replay beside them, one rubric, and a signed release check (chosen).** All are manual or deterministic.

## Decision

Option 9.

1. **Four layers check quality. Scenario replay is a research tool beside them, not a layer and not a release gate.**

   | Layer | What it checks | When it runs |
   | --- | --- | --- |
   | Deterministic CI | Unit, integration and architecture tests. The must-pass scenarios on the packaged server with every integration faked and a scripted model. Exact detectors for leaks across people. | Every pull request for scenarios without a sandbox. The merge queue for scenarios with a sandbox. |
   | The release evaluation cases | 20 to 50 cases from real failures, single-turn and short multi-turn, on the reference local model | By hand, before each release |
   | Shared conversations | Conversations that people choose to share with the maintainers, read for error analysis | By hand, as they arrive |
   | The contact simulation | Contact timing and frequency over simulated weeks | By hand, before each release |

2. **One rubric.** Guidance passes when it is correct, specific, actionable, consistent with goals and history, and warranted.
   - The release evaluation cases and scenario replay use the same rubric.
   - Each judged dimension refines one of these five criteria.
3. **The release evaluation cases run on the model that ships.**
   - Before a candidate is graded, the release plan pins the reference model, the judge and the spot-check rule.
   - Hephaestus runs a rubric judge from a different model family. Its grades are secondary measures. A judged hard fail is evidence for review, not a measured rate.
   - The release owner spot-checks a sample of the judge's grades, and the report shows each disagreement.
   - Each case registers its pass rule and k before the first run. A case passes only when it passes all k trials (pass^k). Ichnaea reports pass^k and pass@k.
   - The cases include a case for sycophancy caused by memory. A deterministic check finds outcome phrases about a person's work without a card.
4. **"Share this conversation with the maintainers" is opt-in.** The person sees a preview and can revoke the share. A case written from a share keeps the failure and drops every production text.
5. **The contact simulation checks only contact timing and frequency.**
   - It checks the limits, repetition, quiet hours, the ask after 3 unanswered messages and the goal cap.
   - Simulated people have state in code, such as availability and reply habits. They live on an injectable clock across workspaces.
   - A maintainer runs `vp run eval:simulate` by hand. It uses local models through the same LLM proxy, at zero model cost by default.
   - It reports exact counts of contact-rule breaches and repetition, and ten transcripts for a person to read.
   - A release passes only with zero breaches of a hard contact rule.
6. **Real traces are a reference, never a calibration.** People who allow research use give structure only ([ADR 0141](0141-research-datasets-build-on-the-research-module.md)). The simulation replays their starting states and reports the differences.
7. **Scenario replay is a research tool beside the four layers, not a release gate.**
   - It runs the packaged server on the controlled clock with every integration faked. An importer turns a slice of a GitHub or GitLab history into events in time order.
   - No step reads an event later than its own time. This holds for webhook payloads, reads, search, pagination, the initial sync and model requests.
   - Developer reactions are recorded from the archive where it has them. Where it does not, they are generated once and then frozen. Each reaction says which it is.
   - Each run writes the ordered observation stream: every message and copy, and every silence with its reason. A silence can be a declined start, `NO_REPLY`, a withheld copy, a contact-policy hold or `STUDY_SILENCE`.
   - Switches turn goals, memory and history off. They are arm settings, and their effect is checked in the stream. They exist only with the controlled clock.
   - Compared arms make fresh model calls. A recorded cassette serves only an exact rerun.
   - Archive data stays in the approved research environment, with a local model by default. Experts review blinded transcripts there. A failure found in replay becomes a release evaluation case only as a synthetic reproduction.
8. **The release check is manual.** A maintainer runs it on the packaged artifact of the candidate and signs it in the release plan. `docs/contributor/release-management.mdx` is its one home when the check ships. Until then, `mentoring-evaluation.mdx` lists its items. It includes the composer's isolation tests on the packaged artifact, the restore drill and the upgrade drill. It also includes a red-team pass and a walk through the first week.
9. **No engagement metric is a target.** Reply rates and "Not now" rates are never goals.
10. **Ichnaea owns the generic evaluation. Hephaestus owns what is specific to it.**
    - Ichnaea holds the case envelope, plans, trial records, decisions, reports and the analysis of calibration labels.
    - Hephaestus holds the scenario format inside the case and the packaged server with its fakes.
    - Hephaestus holds the settle barrier and the observation stream.
    - Hephaestus also holds the detectors, the rubric, judge execution and the study's access rules.
11. **One scenario format, one driver, one scorer.** The must-pass scenarios, the release cases, the contact simulation and scenario replay share them. A CI test asserts the scorer's values. Every other run hands the same values to Ichnaea.
12. **A scenario step ends only when the server is settled.**
    - Settled is a fixed point of the work that can run at the current virtual time.
    - It covers the observations up to the step's watermark.
    - The barrier returns future due times and declared blocked states to the driver. It does not wait for them.
    - Unexplained active work beyond a bound is a capture failure, never a silence.
13. **Unprompted guidance needs measured agreement.**
    - A practice gives unprompted messages only when its AI-rater agreement reaches its rater-rater agreement on the same cases. That result is its qualification.
    - The release evaluation measures both for each practice. Ichnaea's calibration analysis computes them from the labels that Hephaestus collects.
    - A qualification is keyed by the practice version and the model. The release plan records each qualification, and the server reads it.
    - A missing qualification, or one whose key differs from the workspace's practice version and model, fails closed. The practice then gives no unprompted message.
    - The contact policy of ADR 0139 (proposed in #4865) checks the qualification before the draw of [ADR 0149](0149-a-delivery-experiment-draws-only-unprompted-contact-per-person-and-per-decision.md). The draw's Critical exception never bypasses it.
    - Requested guidance, withholding notices and the step-back message are not gated.
    - A practice without a qualification still shows its results on the item card and the practice page. Other ADRs cite this decision.

### What this changes in other ADRs

No ADR changes. [Qualify a release](../contributor/qualify-a-release.mdx) and [Release management](../contributor/release-management.mdx) get the release check when this ADR is accepted.

## Consequences

- Good: a release has an evaluation record at almost no model cost.
- Good: contact rules are checked by exact counts, not by opinion.
- Good: the evaluation measures no person and runs no experiment on people.
- Good: a replay shows every silence with its reason, over a real project.
- Good: the composer decision, the release cases and scenario replay share one record format and one report.
- Bad: quality regressions between releases show only in shared conversations or in the next release check.
- Bad: the release check takes a maintainer's time for each release.
- Bad: the evaluation cases grow only as fast as real failures are found and rewritten.
- Bad: a replay scenario needs a pseudonymized history and reactions that someone writes by hand.
- Bad: scenarios with a sandbox need a Kubernetes cluster. They run in the merge queue, not on each pull request.
- Bad: a workspace on a model or practice version that no release qualified gets no unprompted messages for that practice.
- Bad: some Ichnaea parts that this ADR relies on, such as pass^k in the report, land in Ichnaea first.

## Confirmation

Not enforced. The implementing issues add the checks below, and review of each implementing pull request catches a missing one. They are:

- The detectors' positive and negative controls run on fixture streams in CI.
- A packaged-server scenario runs on every pull request that builds the server. A sandbox scenario runs in the merge queue.
- Two runs of one scenario from fresh state give equal canonical traces. A delayed response does not change the trace.
- `vp run eval:simulate` fails when a hard contact rule has a breach count above zero.
- A contact-policy test fails when a practice without a current qualification starts an unprompted message (decision 13).
  The test includes a Critical problem.
- A scenario with a quiet-hours hold settles and returns the due time.
  A scenario with an uncertain provider write settles and returns the blocked state.
- ReplayNoLookAheadTest fails when a replay reads a later event. FrozenReactionsTest fails when two runs differ.
  SilenceReasonBundleTest fails when a silence has no reason.
- The release check is a checklist in `docs/contributor/release-management.mdx`. Review of the release plan catches an unsigned check. It also catches a check without its pinned model, judge and spot-check rule.

## Revisit trigger

- A defect that a release check should have found reaches a release twice.
- The project gets a rating budget and a consented panel of people.

## Sources

- Yao et al., [τ-bench](https://arxiv.org/abs/2406.12045). pass^k measures whether an agent succeeds on every one of k trials, which shows consistency that pass@1 hides. This gives decision 3.
- Zheng et al., [Judging LLM-as-a-Judge](https://arxiv.org/abs/2306.05685). Judges favor their own outputs and longer answers. This gives a judge from a different family and a person's spot-check.
