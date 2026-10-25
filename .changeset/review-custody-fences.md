---
"hephaestus": patch
---

A review attempt that was cancelled or requeued can no longer record its late result over the attempt that replaced it. Its runner now stops at its next model call of the review conversation, and a worker that is offered the requeued review stops its own earlier attempt first. Heph replies are no longer restarted when the clocks of the server and the worker differ, because the reply lease now uses the database clock.
