---
"hephaestus": minor
---

A worker now runs as many reviews as `worker.reviews` says, up to 64. Before, one worker stopped at 16 reviews and at 4 compositions at once, whatever the setting. The composition limits and the threads that the heap leaves room for now follow `worker.reviews`. Up to 8 reviews, the composition limits stay as they were. A review sandbox now requests 50m of CPU instead of 100m, which leaves `conversationTurns: auto` more room. Operators take no action.
