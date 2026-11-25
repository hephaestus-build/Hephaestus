---
"hephaestus": patch
---

Reviews no longer all run out of time together when the review model is busy. A review that checks several practices now counts as one model call per practice when a model pool decides whether it can take another review. On a busy pool, fewer reviews run at once, so the reviews that run share fewer model calls. Before, a pool took a new review as soon as no call waited, so reviews of six practices filled it far past its limit, and many ended with no practice result. `agent_review_pool_held` counts the reviews that a dispatch pass held.
