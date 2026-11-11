---
"hephaestus": minor
---

Person data erasure now waits for review sandboxes and their gateway archives to be removed before it acknowledges evidence removal. Erasure can release an attempt whose preparation is waiting for capture admission.

**Operators:** stop the old workers. Before starting the new workers, remove their remaining sandbox claims with foreground deletion. Follow the [Planned node maintenance guide](https://docs.hephaestus.build/admin/maintenance#planned-node-maintenance). Existing claims keep their old expiry policy. New claims wait for sandbox and Pod termination before expiry deletion completes.
