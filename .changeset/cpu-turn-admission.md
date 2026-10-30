---
"hephaestus": minor
---

The single-node Kubernetes profiles now admit up to 32 conversation turns from the declared CPU budget. Provider calls keep their shared pool limits. Tools start on demand within the unchanged quota of ten tools Pods, including up to two warm Pods. Turns that wait for tools show their progress and record a wait duration.

**Operators:** The `small` profile now stops the old worker before starting its replacement. The `small-16gib` profile keeps that strategy. Reviews and conversations pause during the drain and restart. Budget this deployment outage against the 99.5% availability target. An explicit conversation-turn count still overrides the automatic value. No Pod request or limit is reduced.
