---
"hephaestus": patch
---

A review whose job still waits for a worker, for example behind the concurrency limit, now reads Queued instead of Running everywhere: the review list and its status filter, the review's header and history, the overview counts and a developer's own reviews.
