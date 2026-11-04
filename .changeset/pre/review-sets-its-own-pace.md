---
"hephaestus": minor
---

A practice review now sets its own pace, so it accepts any number of practices. It starts the next practice while the model provider answers and the review's Pod has memory for it, and it stops widening when a call has to wait. **Operators:** remove `PRACTICE_REVIEW_PRACTICE_CONCURRENCY`, because Hephaestus no longer reads it. If you set `PRACTICE_REVIEW_PRACTICE_MODEL_CALLS` or `PRACTICE_REVIEW_PRACTICE_OUTPUT_TOKENS`, multiply the value by 3: each practice now gets exactly what you set, and the defaults are 36 and 48000.
