---
"hephaestus": patch
---

Reviews no longer all run out of time together when the review model is busy. A review that checks several practices now counts as one model call per practice when a model pool decides whether it can take another review. So a pool starts only the reviews that it can serve, and each one finishes its practices. Before, a pool took a new review as soon as no call waited, so reviews of six practices filled it far past its limit, and each one ended with no practice result.
