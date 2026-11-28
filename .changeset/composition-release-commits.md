---
"hephaestus": patch
---

When a review's composition ends, Hephaestus now removes the inputs that it kept for the composition, and it records a repair whose retries are spent or resumes a repair that waits for the composition. Before, this step ran after the end committed but without a transaction of its own, so it failed each time and logged "Could not remove original composition inputs".
