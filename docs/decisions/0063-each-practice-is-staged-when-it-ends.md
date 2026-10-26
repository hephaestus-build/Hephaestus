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
