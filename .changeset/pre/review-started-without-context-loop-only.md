---
"hephaestus": patch
---

The `review.started` diagnostic no longer carries `context_loop_only`. The field was always false: a sandbox runs in one container, so no context volume belongs to the review loop alone.
