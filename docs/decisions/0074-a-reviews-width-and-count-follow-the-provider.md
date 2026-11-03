# ADR 0074: A review's width and count follow the provider

**Status:** Accepted
**Date:** 2026-11-02
**Authors:** Felix T.J. Dietrich

**Amends:**

- [ADR 0068](0068-reviews-and-heph-run-on-one-agent-runtime.md): § Fit by measured use gets new values, [decision G](0068-reviews-and-heph-run-on-one-agent-runtime.md#g-review-pod-admission-checks-quota-and-provider-demand) no longer blocks a claim on one waiting call, and the revisit trigger of option Z changes.
- [ADR 0069](0069-practices-run-when-ready-and-stay-current-by-their-sources.md): a request on an unchanged item reuses current results, and [D10](0069-practices-run-when-ready-and-stay-current-by-their-sources.md#d10-practice-concurrency) gets a measured maximum.

Each amended ADR has a dated update block that points here.

## Context

One requested review holds a review agent Pod for 12.5 minutes and makes 70 to 84 model calls.
The single-node profiles run one review at a time, and the earlier estimate for one node was 9 to 10.
That is too few for an install of any size.

Five full reviews on the staging install (130 practice sessions, 2026-10-30 to 2026-11-01) show where the time goes.
They ran on small test pull requests with one model, so a real pull request costs more.

| Part of the 751.9 s that review 63915780 held its Pod | Time | Share |
| --- | ---: | ---: |
| Capture, once for the review | 8.4 s | 1.1 % |
| Pod start and stop | 19.0 s | 2.5 % |
| Practice sessions | 724.6 s | 96.4 % |

| Measured in the five reviews | Value |
| --- | --- |
| Model time in a session | 96 % |
| Output tokens in model time | 94 %, at about 40.5 tokens per second for each stream |
| Time for each call before output | 0.86 s |
| Prompt tokens read from the cache | 73 to 83 % |
| Model calls on results that ended Does not apply | 31.1 % |
| Sessions refused for unread checks that their brief left out | 28 %, each with about three more calls |
| Calls on practices outside the catalog that overlap a catalog practice | 26.2 % |
| Same practice on the same item version, same outcome again | 90.1 % of 1,419 pairs |

Composition holds no sandbox slot, so it is not part of the Pod's time.

A review agent Pod requests 640 MiB because each running custom script kept its own Node process alive for the whole model session.
A measurement at 3, 6, 12 and 27 practices at once, with the runner and image of 1.0.0-rc.1 and a fake provider at the rates above, shows what grows with width:

| Practices at once | Before: peak, time in the Pod | After this decision: peak, time in the Pod |
| ---: | ---: | ---: |
| 3 | 340 MiB, 610 s | 239 MiB, 607 s |
| 6 | 519 MiB, 328 s | 282 MiB, 322 s |
| 12 | 814 MiB, 205 s | 296 MiB, 205 s |
| 27 | Killed for memory at 13 s | 308 MiB, 143 s |
| 27, a change of 20 files and 6,000 lines | Killed for memory at 28 s | 563 MiB, 316 s |

Each script process held about 56 MiB. Each session in the runner holds about 3 MiB.
The times assume a provider that keeps its rate at every width.

## Decision drivers

- Non-negotiable 1: feedback earns trust or it is not sent. A faster review must not lose a Not met result or invent a claim.
- The model's output rate is the first limit. Pods, memory, database connections and the claim query are not.
- No new service and no new runtime. The same pipeline and the same trust rules.
- An operator does not need to know a provider's limit.
- Avoid fragmentation: one decision for the review path, in one place.

## Considered options

1. **More Pods at width 3.** Rejected. Each Pod costs 640 MiB to give 3 model streams, and a review still takes 12 minutes.
2. **Wider Pods with fewer and cheaper calls.** Chosen.
3. **One shared loop host (ADR 0068 option Z).** Not built. See [Shared loop host](#shared-loop-host).
4. **Several practices in one session.** Not built. See [Several practices in one session](#several-practices-in-one-session).

## Decision

**A review Pod is wide and small, and the provider sets the pace.**
The targets are one requested review in under 3 minutes of Pod time on the sample pull requests, at most 30 calls for each requested review, a Pod request of at most 384 MiB, at most 30 MiB of worker memory for each running review, and 32 reviews on one worker.
More workers scale linearly. A bench proves 64 reviews with a fake provider.

### Width

- **A script ends at `ctx.review`.** The host ends the script's process when the script calls it, and it returns the session's result. Code after the call does not run. The contract already said to return the result. A failed review ends with the review's reason and gives no result.
- **At most two script processes load at once in a Pod.** A script gives its room back when its process ends, at its first model call, or after 10 seconds, so one slow script cannot starve the others.
- **`practice-concurrency` defaults to 27.** It is a bound on work in the Pod, not a rate. The call pool sets the rate.
- **The Pod requests 384 MiB.** The limit stays 1,280 MiB, because a large change can pass the request.

### Selection

- **A skip is not a claim.** A rule may record Does not apply only when it proves it from complete captured evidence. A selection by model records its own reason and no result, and it shows nothing about the person's work.
- **Selection fails open.** Unsure, a parse failure or a provider error means that the practice runs.
- A rule's recall is measured against existing results before it ships. A rule that loses a Not met result does not ship as it is.

### No repeat work

- **A request on an unchanged item reuses a current result** when the practice version and the declared sources did not change. This amends ADR 0069, where a request is a milestone that measures everything again.
- A newer occasion replaces a queued review at claim, and it inherits that review's selection, as ADR 0069 already decided. An `opened` review selects practices that a `synchronized` window does not.

### Provider

- **Every model is in a call pool by default**, with an adaptive limit that follows the provider's 429 responses and `Retry-After`. Nobody has to guess a provider's limit. There is no separate component.
- The pool is fair between reviews and workspaces, and conversations stay protected.
- **Decision G changes.** Today a claim starts no Pod while the pool has one live background waiter. A wide review has waiters whenever the pool is below its width, so that rule would keep a second review from ever starting. A waiter alone no longer blocks a claim, and the pool's fair order shares the grants.

### Capacity

Chart limits, composition and the turn budget follow `worker.reviews`.
Composition and its active limit move together: the active limit alone saturates at about 8 reviews on a real model, and raising only the total makes composers wait until they withhold feedback.

### Shared loop host

Not built, unless the bench shows that Pods are still the first limit after these changes.

One loop for each Pod costs about 160 MiB of code and base heap, and a session adds about 3 MiB.
At 32 reviews a host for each worker would save about 5 GiB (estimated), and 32 Pods at 384 MiB fit the 13 GiB of room on a 32 GiB node.
The provider binds long before that: 32 reviews at this width want about 445 model streams on average.
The reasons of ADR 0068 still hold, and a large change makes the first one worse: one V8 heap with several 300 MiB loops has no limit for each review.
A host would also need resume through PostgreSQL and a QuickJS instance for each attempt.

### Several practices in one session

Not built. `PI_PRACTICE_BATCH_SIZE` does not batch sessions: it only divides a turn's budget. Each practice has had a session of its own since [#2519](https://github.com/hephaestus-build/Hephaestus/pull/2519), which fixed one practice's reasoning carrying into the next.

A comparison on the real model had up to six related practices in one call against one practice in each call, with no tools:

| 2 small pull requests, 44 practice results | One practice in each call | Up to six in each call |
| --- | ---: | ---: |
| Calls | 44 | 18 |
| Output tokens | 29,199 | 20,359 |
| Same outcome as the stored result | 40 of 43 | 39 of 42 |
| Stored Not met results lost, of 9 across 4 pull requests | 0 | 0 |

The saving is real, but the comparison does not prove the change.
The diffs held 283 to 816 characters, citations were not verified, no coverage was measured, and both arms are near the 90.1 % noise floor.
The same no-tool calls used 664 output tokens against 2,356 for a real session.
This points at the larger gain: fewer calls in each session, not fewer sessions.

## Consequences

- Positive: at these widths a review takes under 3 minutes of Pod time on the sample pull requests, if the provider holds its rate. One node holds more reviews, and the provider's rate decides how many run at once.
- Positive: a 429 slows every caller, with no setting.
- Negative: code after `ctx.review` no longer runs. A stored script that changed a result after the call loses that step. The migration guide says so.
- Negative: a Pod can pass its request on a large change, so it is among the first to go if a node runs out of memory.
- Negative: one review can ask for 27 model streams. The pool's fairness is what stops it from taking all of them.
- **Order matters.** With no pool, a provider limit of 12 calls at width 27 lost no result, and the review took 268 s instead of 143 s. A limit of 3 lost 20 of the 27 practices at width 27 and 14 at width 12: the sessions ran out of retries, and each practice ended unavailable, with no claim about the work. So the default width ships only after the adaptive limit.
- Unproven: no real provider has run at these widths. The model's rate at 6 streams and above is not measured.

## Revisit trigger

- The bench at 64 reviews shows that Pods or memory are the first limit: reopen the shared loop host.
- A pool's adaptive limit oscillates in the 429 step of the bench: reconsider a fixed limit for that provider.
- An eval with verified citations on real pull requests shows that batching keeps outcomes: reconsider several practices in one session.
- A rule or a selection check loses Not met results beyond the noise floor: withdraw it.
