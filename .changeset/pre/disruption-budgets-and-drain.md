---
"hephaestus": minor
---

Accidental node drains now stop before they evict the bundled database, broker, server, or only worker. Multiple workers still allow one eviction at a time. Workers normally drain for thirty minutes, with two more minutes for shutdown; the 16 GiB profile keeps its ten-minute drain.

**Operators:** Use the planned node maintenance procedure to drain workers before you remove the release's disruption budgets. Reapply the verified release afterward to restore the budgets and replica counts.
