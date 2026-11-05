---
"hephaestus": patch
---

A practice review that stops with some practices still without a result, for example because one practice reached its deadline, no longer looks like a failed sandbox in the worker log. The worker logs it at INFO as `partial`, and `sandbox.review.without_result` names each open practice by its position and stop code, such as `practice=9 program_stop=deadline`. The `sandbox.exits` metric counts it under the reason `partial` instead of `error`, and `agent.job.stage.duration` times its sandbox stage with the outcome `partial` instead of `failed`. As before, the next attempt continues the review with only the open practices and spends no retry.
