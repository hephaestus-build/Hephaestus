---
"hephaestus": minor
---

Each practice group in a review now gets independent limits for model calls and output tokens per practice. Operators can set them with `PRACTICE_REVIEW_PRACTICE_MODEL_CALLS` (default 12) and `PRACTICE_REVIEW_PRACTICE_OUTPUT_TOKENS` (default 16000). The model binding's timeout remains the whole-review safety ceiling, so later practices may not be reached when an earlier group takes a long time. A group that shows no sign of life for five minutes stops instead of waiting for that ceiling. When a long review fills the model's context, the next practice group gets the captured work again.
