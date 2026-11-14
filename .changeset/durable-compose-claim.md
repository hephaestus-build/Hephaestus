---
"hephaestus": minor
---

A review composition keeps its claim decision when its sandbox continues on another server replica. The decision is stored in PostgreSQL, so a stopped server no longer loses it.

**Operators:** Before the first upgrade, drain active review compositions as **Drain legacy review compositions** describes. Later upgrades use normal rolling updates. Set `server.databasePool.maximumPoolSize` to `auto`, or raise an explicit value to the chart's validated floor. Keep the derived PostgreSQL connection budget.
