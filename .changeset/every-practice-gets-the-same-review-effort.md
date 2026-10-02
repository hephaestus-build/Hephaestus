---
"hephaestus": minor
---

Every practice in a review now gets the same effort, however many others the review carries and however fast the model answers. Each practice group may make a set number of model calls and write a set number of output tokens per practice, and a slow group no longer takes anything from the groups after it. The limits only stop a group that runs away, so a review spends what its practices need. Operators can set them with the new `PRACTICE_REVIEW_PRACTICE_MODEL_CALLS` (default 12) and `PRACTICE_REVIEW_PRACTICE_OUTPUT_TOKENS` (default 16000). The timeout of the practice-review model binding now only stops a review that is stuck, and a review that shows no sign of life for five minutes stops that group instead of waiting for the timeout. When a long review fills the model's context, the next practice group gets the captured work again, so later practices are still judged on the exact text.
