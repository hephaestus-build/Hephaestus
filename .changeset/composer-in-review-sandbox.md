---
"hephaestus": minor
---

A review's feedback is now written in the review's own sandbox, in a run that follows the review's measurement, instead of on the worker. The model calls go through the same model gateway and budget as the review's own calls. The worker no longer reads `AGENT_MAX_ACTIVE_COMPOSITIONS` or `AGENT_MAX_CONCURRENT_COMPOSITIONS`, and the Helm chart no longer sets them; you can remove them from your configuration. `SANDBOX_MAX_CONCURRENT` alone bounds the reviews that one worker runs.
