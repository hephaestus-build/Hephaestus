# ADR 0063: Each practice is staged when it ends, and the review seals once

**Status:** Accepted
**Date:** 2026-10-26
**Authors:** Felix T.J. Dietrich

**Builds on:** [ADR 0058](0058-the-helm-chart-is-the-only-supported-deployment-from-1-0.md) for the sandbox Job, and [ADR 0061](0061-review-seals-then-feedback-composes.md) for the seal.

## Context

On Kubernetes, the worker starts one sandbox Job for each review attempt.
All practices of the review run in that Pod.
The runner keeps every candidate in the Pod's `emptyDir` and submits them together at the end.

On staging, a review of 32 practices ran 16 minutes 38 seconds and made 98 gateway calls (measured on 2026-10-09).
Thus, an evicted Pod, a lost worker or a deadline loses every measured practice.
The retry measures all practices again, possibly on another worker, at full model cost.

## Decision drivers

- A practice that ended does not run again.
- The admission rules do not change. The same verifier checks every candidate against the captured sources.
- Results become visible as one consistent set for each review, under the item lock.
- No new table.

## Considered options

1. **Admit all candidates at the end (current).** Rejected. One loss loses everything.
2. **Write each result at once.** Rejected. Results of one review would appear one by one.
   A later failure could leave a mixed set under one review.
3. **Stage each practice when it ends, and seal the review once.** Chosen.
4. **Checkpoint inside a practice (session resume).** Rejected. A practice is the step. Model sessions are not durable state.

## Decision

The step is one practice.

**Stage.** When a practice ends, the runner submits its candidate to the gateway.
The worker admits it at once, with the admission service that admits candidates today (`ReviewOutputService`).
It checks shape, outcome rules, coverage, quotes and claim basis.
The worker stores the admitted candidate on its `review_item` row, with state `STAGED` and the number of the attempt that staged it.
The row also keeps the cited texts that composition needs.
A refused candidate is withheld at once with its refusal.
The job's fence (job, attempt and worker) guards each write.

**Seal.** [ADR 0061](0061-review-seals-then-feedback-composes.md) defines when a review seals.
The seal is one transaction under the item lock.
It checks the fence and the currentness of the item version.
Then it writes the results and problems of the staged rows.
Composition starts after the seal.

**Retry.** A new attempt of the same job runs only the practices that are not staged.
A staged practice stays staged for the review.
The review pins the practice version, the method and the item version, so a new attempt cannot change what the staged candidate measured.

What survives a loss, for each state:

| State | Where it is kept | What a loss does |
| --- | --- | --- |
| Item event and its wait | `item_event` | Nothing is lost. |
| Review membership | `review_item` | Nothing is lost. |
| Attempt, lease and fence | `review_job`, `worker_registry` | The sweeper puts the job back in the queue. |
| Staged candidate | `review_item` (`STAGED`) | Nothing is lost. The next attempt keeps it. |
| Sandbox files and model sessions | the Pod's `emptyDir` | The practices that were running run again. |
| Results, problems and lineage | `result`, `problem` | Nothing is lost. |
| Prepared and sent feedback | the feedback pieces | Nothing is lost. A send is never repeated automatically. |
| Standing and item practice state | calculated when read | The next read calculates them again. |

## Consequences

- A lost Pod costs at most the practices that were running.
- Each practice makes one more gateway request, to stage. The gateway allows each sandbox 120 requests per minute by default (`SANDBOX_API_REQUESTS_PER_MINUTE`). The measured review above made about 6 per minute.
- A staged candidate keeps its cited texts. Thus, the seal and composition do not need the folder of an earlier attempt.
- The cited texts are a copy of captured work. They follow the retention and erasure of the composition basis, and the erasure catalog lists them.
- One migration adds the staging columns to `review_item` and the `STAGED` state.

## Revisit trigger

Revisit when one practice runs long enough that its own loss is costly.
Revisit when composition needs the result of one practice before the review seals.

## Update — 2026-10-27 (a retry keeps what an earlier attempt withheld)

[#3313](https://github.com/hephaestus-build/Hephaestus/pull/3313) ships the part of **Retry** that applies before staging exists.
A retry of the same job leaves out every practice whose membership an earlier attempt ended.
It does not capture or measure that practice again.
Thus, a readiness refusal that a source caused because it was not ready then stays a withholding for that item version.
We accept this: the refusal has its own recorded reason, and a newer item version gets a new review.

Today every ended membership before admission is a capture withholding.
A requeued job that an earlier attempt admitted continues with composition and does not prepare again (`ResultAdmissionService.isContinuation`).
Thus, a retry that has no open practice cannot occur yet.
When per-practice staging ships, a retry can find every practice staged.
That retry must seal and compose, not record a readiness refusal.

## Update — 2026-10-28 (#3403): freshness at the seal

[ADR 0069](0069-practices-run-when-ready-and-stay-current-by-their-sources.md) amends § Decision, items Seal and Retry.
The seal checks each staged result by the fingerprints of the sources that its session could read.
A staged result that is stale at the seal is admitted and ended at once as `WORK_CHANGED`. It is never delivered.
A retry at a moved item version seals the staged results, and the other practices end with `REPLACED`.

## Update — 2026-10-30: durable staging under the attempt lease

The current attempt ownership check and unexpired `review_job.leased_until` replace the worker-registry fence above.
Staged records carry no worker ownership.
[ADR 0068](0068-reviews-and-heph-run-on-one-agent-runtime.md) owns the runtime attempt fence; its replacement must preserve staged work, the admission marker and measured usage.
The server uses the existing candidate and evidence verifier before it stages a completed practice.
It keeps the verified candidate, exact cited text, pinned standard, request digest and capture receipt on the existing `review_item` row.
A separate mapping reads its large payload only for staging, sealing or erasure.
Ordinary review reads count `STAGED` as pending and expose no candidate or problem.

The runner stages a completed result or terminal runtime refusal before it starts another practice in that execution slot.
A transient provider failure, execution error or interrupted deadline leaves the practice open for retry.
At the retry limit, the server withholds open practices with the recorded stop cause.
A terminal runtime refusal or a candidate refusal keeps its own withholding reason.
An identical repeated request returns its receipt; a changed request returns `409 Conflict` under the existing attempt-conflict contract.
A stage request makes no model call and records no second model charge.
The existing attempt measurement and usage ledger retain spend before recovery resets the attempt counters.
An unanswered model call stays unverifiable under that ledger's rules.

The seal reads the verified candidates from membership rows under the existing item and review locks.
It creates results and problems in one transaction and records the existing admission marker for composition.
Each capture attempt keeps one shared composition root. It carries no admission digest before the seal.
The seal merges the staged cited text into this root, with content digests and capture receipts.
Cited text from an older attempt does not depend on its Pod or local folder.
Composition remains an LLM job and uses the existing durable handoff after admission.

| Failure | Recorded behavior |
| --- | --- |
| The Pod or worker is lost after staging. | The next attempt captures and measures only open practices. Recorded withholdings and staged candidates remain. |
| A stage response is lost after commit. | The same request gets the retained receipt. A changed candidate cannot replace it. |
| An expired or replaced attempt writes late. | The current worker, attempt and lease checks refuse the write. |
| Every practice already ended. | The retry seals and composes without a capture or sandbox. It records no readiness refusal. |
| Capture proved that a practice does not apply. | The practice is not open work. Its seal records `DOES_NOT_APPLY`, including after an interrupted attempt. |
| The work changed before the retry. | The retry seals the staged set and ends open memberships as `REPLACED`. The existing conservative currentness check ends stale results as `WORK_CHANGED`. |
| Measurement retries are exhausted after a lost worker. | A handoff only seals retained work and withholds open practices and feedback. It starts no sandbox or model session. |
| Two seal requests race. | The lock and admission digest permit one result set. The second request reads that set. |
| A candidate is read before the seal. | It has no result or problem row, so it changes no visible feedback or standing. |
| A source copy is revoked or expires. | Its staged practice is withheld as `COPY_AUTHORITY_UNAVAILABLE`. The seal keeps sibling practices that the existing job-level custody policy permits. An invalid root cannot be composed from. |
| A person erasure removes a membership. | The seal cannot recreate it. Erasure removes its retained composition basis. |
| A review is cancelled before its seal. | Terminal settlement withholds staged practices with `CANCELLED` and removes their staged bytes. |
| Retained input expires or the job ends. | The existing bounded retention pass removes staging bytes. An unsealed staged membership keeps an explicit withholding. |
| The worker is lost after sealing. | Recovery continues composition from the admission marker and retained basis. It does not measure or admit again. |

The final finishing handoff requires a staged candidate.
If only runtime withholdings remain, the existing retry limit can end the job before the seal.
A captured inapplicability can then remain an execution withholding.

[ADR 0069](0069-practices-run-when-ready-and-stay-current-by-their-sources.md) owns source fingerprints and later publication changes.
Until its loop-only prerequisite ships, currentness keeps the existing conservative item version.
This layer adds no provider writer, composition template or separate scheduling queue.
