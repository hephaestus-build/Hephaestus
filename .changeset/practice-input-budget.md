---
"hephaestus": patch
---

Each practice in a review now also has an input-token budget, so a practice that keeps rereading large files can no longer run up the cost of a review. `PRACTICE_REVIEW_PRACTICE_INPUT_TOKENS` (`hephaestus.practice-review.practice-input-tokens`, default 342000) sets it; it counts every prompt the practice's model calls send, cached or not. A practice that spends it ends as not reached with the reason budget, and the other practices of the review go on.
