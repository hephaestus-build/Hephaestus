---
"hephaestus": patch
---

When a review's composition ends, Hephaestus now stops a repair whose retries are spent, or resumes a repair that waits for the composition. It also removes the inputs that it kept for the composition, unless a repair still waits for them or they hold the composition's record of tool calls. That record stays until the inputs expire. Before, this step ran after the end committed but without a transaction of its own, so it failed each time and logged "Could not remove original composition inputs".
