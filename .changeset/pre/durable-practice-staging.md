---
"hephaestus": minor
---

A stopped practice review can continue without repeating practices that finished. It keeps the recorded model usage from earlier attempts. Results become visible together after the review seals.

The private review runtime stages each completed practice at `/internal/llm/stage-result`. Clients that call `/internal/llm/admit-results` must send `reviewId`, `jobId`, `attempt` and `contractVersion`, and omit `results` and `nonResults`. The server and bundled runner update together.
