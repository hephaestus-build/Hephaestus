---
"hephaestus": minor
---

A model provider outage no longer uses up a review's retries. When the provider answers none of a review's calls, or limits their rate, the review waits with a growing interval and tries again. If the provider stays unavailable for too long, the review closes as Partial with the stop reason *Provider unavailable*. The limit is two hours from the submission, for the measurement and the feedback composition together. The review keeps its admitted results and makes no claim about the work. An error that the provider answers still uses a retry. `agent_job_infra_retry_requeued_total` keeps counting each wait, with the cause `provider_unreachable` or the new cause `rate_limited`, and `HephaestusReviewUnreachable` fires for both.

**Operators:** Stop every role before this upgrade, as **Stop every role before recording provider-unavailable reviews** describes. The previous server cannot read the new stop reason. To change the two-hour bound, set the optional `hephaestus.agent.capacity.model-outage-window`.
