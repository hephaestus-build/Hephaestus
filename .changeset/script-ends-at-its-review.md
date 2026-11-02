---
"hephaestus": minor
---

A practice script now ends when it calls `ctx.review`, so a review holds less memory while the model works. Code after `ctx.review` no longer runs, and the review's result is the practice's result.

**Operators:** if a practice script of yours changes the result after `ctx.review`, move that logic before the call or into its directions.
