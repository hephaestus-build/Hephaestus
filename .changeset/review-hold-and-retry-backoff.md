---
"hephaestus": minor
---

A review that its model pool cannot take when it starts now gives its sandbox back. It waits with the hold `model_pool` in `agent_run_runs`, keeps its place and spends no attempt, and it gets a new sandbox once the pool admits it. Before, its sandbox stayed and asked for the review again and again, and it kept the place of other reviews of the same pool. A review with more practices than its pool can serve at once now measures them in waves of that size.

Failed durable server work, such as an email or an account export, now waits longer between its attempts: 5 minutes after the first failure, doubling up to one hour. Its ten attempts in all, the first included, span about six hours, also when several servers run. Before, two servers could spend all ten attempts in about 25 minutes, so a short outage could exhaust work that would have recovered. `event_publications_exhausted` now counts exhausted work at once, and no longer counts a last attempt that still runs.

Commit link correction no longer spends an email's attempts while GitHub throttles it. A rate limit or a secondary limit, also one that GitHub answers with HTTP 200, now makes the email wait, and it ends unanswered only after five real misses. The job then pauses that repository, from 10 minutes up to one hour, and corrects the other repositories meanwhile. `commit_identity_resolutions_total` counts these emails as `deferred`. Before, a throttle during a recovery could end commits that GitHub can read as unanswered, and one throttled repository could hold up all others. **Correct commit links** now asks again only for the GitHub emails of the affected window, with a preview and a transaction.

**Operators:** stop every role for this upgrade, because a server of the previous release cannot read the new hold.
