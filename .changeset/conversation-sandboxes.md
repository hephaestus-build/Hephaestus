---
"hephaestus": minor
---

Conversation turns now run in their conversation's sandbox through the same gateway and lease as practice reviews. Commands, search, and context tools run in that sandbox's container. Answer updates reach the app through the live hub.

**Operators:** Stop the old conversation loop and drain the servers before this upgrade. Remove the old loop and runtime settings as described in the migration note. Rename `conversationLoop.turns` to `sandbox.conversationTurns`. The chart counts the actual conversation template requests and removes the old tools-Pod, isolation-probe, and loop reservations. Run the sandbox isolation gate before enabling work.

Automatic turn capacity now fits the declared CPU and memory budget at steady state and during a drained upgrade. The small profile admits eleven turns with backups and fourteen without them, alongside five review slots. It keeps the current template requests; workload peaks have not been measured yet.

**Operators:** `auto` requires `sandbox.budget.cpu` and `sandbox.budget.memory`. The small profile supplies them. Managed clusters have no assumed budget: enter the allocatable CPU and memory assigned to the release before selecting `auto`. An explicit count is checked whenever a budget is supplied, and an oversized count stops chart validation. Finish review work before the upgrade's transient peak.

Idle warm conversation sandboxes yield to queued conversations when all physical slots are occupied. A turn count of zero stops new turns. A busy erasure fence asks the sandbox to retry instead of abandoning its answer. Context loads acquire a capture session only after a load permit is available, and their response task stops at the turn's work deadline.
