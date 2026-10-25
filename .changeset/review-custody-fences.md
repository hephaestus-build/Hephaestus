---
"hephaestus": patch
---

A review attempt that was cancelled or requeued can no longer record its late result over the attempt that replaced it, and its sandbox stops at its next model call. Heph replies are no longer restarted when the clocks of the server and the worker differ, because the reply lease now uses the database clock.
