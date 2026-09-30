---
"hephaestus": patch
---

Queued practice reviews now dispatch correctly in a workspace that binds a separate model for each AI data-handling tier. Each tier's concurrency limit applies only to reviews in that tier, so a busy tier no longer holds back reviews in another one, and a second tier binding no longer stops queued reviews from being picked up.
