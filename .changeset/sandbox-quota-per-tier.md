---
"hephaestus": minor
---

The chart now writes one sandbox quota for reviews and one for Hephaestus conversations, and derives both from your workers' replicas, reviews and conversation turns. Hephaestus's sandboxes can no longer take the room of a review, and reviews can no longer take Hephaestus's. The quotas count what sandboxes request, so a node holds as many reviews as their measured use allows. The single-node profiles now run up to 8 conversation turns at once, so several people can talk to Hephaestus at the same time; their worker now upgrades by stopping first and draining for at most 10 minutes. The Hephaestus host requests 50m of CPU with a limit of one CPU.

**Operators:** remove `worker.sandbox.quota.pods`, `worker.sandbox.quota.cpu` and `worker.sandbox.quota.memory` from your values before you upgrade. The chart refuses them now. `worker.sandbox.quota.enabled` stays. If your sandbox RuntimeClass adds a Pod overhead, such as gVisor or Kata on some clusters, set `worker.runtime.podOverhead` to it, so that the quotas count it. `priorityClasses.reviews` and `priorityClasses.conversations` must now name different classes; the chart refuses to render otherwise. On the single-node profiles, Hephaestus can wait up to a minute after an upgrade, until the new worker removes the old worker's Hephaestus sandboxes.
