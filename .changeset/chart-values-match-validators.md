---
"hephaestus": patch
---

The chart now refuses `config.monitoring.timeframeDays` above 365 and `config.monitoring.cooldownMinutes` outside 1 to 1440 at install. The server already refused those values at start, so a failing Pod becomes a clear install error. On a bundled database without an explicit `max_connections`, the chart now sets what the role pools need, which is 164 at the defaults with workers on. Before, the database allowed 128 at its default memory, so connections could fail under load. PostgreSQL restarts once during the upgrade if you have not set this value.
