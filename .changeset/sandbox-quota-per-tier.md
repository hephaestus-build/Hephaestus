---
"hephaestus": minor
---

The chart now writes one sandbox quota for reviews and one for Heph conversations, and derives both from your workers' replicas, reviews and Heph turns. Heph's sandboxes can no longer take the room of a review, and reviews can no longer take Heph's. The quotas count what sandboxes request, so a node holds as many reviews as their measured use allows. The single-node profiles now run up to 8 Heph turns at once, so several people can talk to Heph at the same time; their worker now upgrades by stopping first and draining for at most 10 minutes. The Heph host requests 50m of CPU with a limit of one CPU.

**Operators:** remove `worker.sandbox.quota.pods`, `worker.sandbox.quota.cpu` and `worker.sandbox.quota.memory` from your values before you upgrade. The chart refuses them now. `worker.sandbox.quota.enabled` stays.
