---
"hephaestus": patch
---

When the model is busy, practice reviews now wait their turn instead of starting together and all running out of time. A model takes another review only when it shows it can serve it, and a waiting review shows as queued, uses no review sandbox, and keeps its whole time and its whole outage allowance for when it starts. Operators can follow it with `agent_review_pool_held` and `agent_review_pool_admission_wait_seconds`.
