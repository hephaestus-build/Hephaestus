---
"hephaestus": patch
---

When a pull or merge request review completes, Hephaestus now keeps a scoped copy of the review's public results and the parts of the work they cite, for at most two hours. This prepares feedback on the work that never reads private data. Nothing posts from the copy yet, and feedback on the work reaches developers as before. Account erasure deletes the copy with the review's other inputs, and the regular review-data cleanup removes it once it expires.
