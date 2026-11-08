---
"hephaestus": minor
---

Provider events of a workspace stay in order when an event takes longer than the broker's acknowledgement wait, and a webhook replay no longer runs at the same time as a live event of the same workspace. A replay that finds another event of the same stream still in progress or waiting for its retry, or another replay of that stream, now returns HTTP 503, so you can try again.

**Operators:** An external NATS broker (`nats.bundled=false`) must run NATS Server 2.11 or later. The bundled broker already does.
