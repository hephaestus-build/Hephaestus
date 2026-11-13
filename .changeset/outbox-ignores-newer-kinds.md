---
"hephaestus": patch
---

During a rolling update, a replica that runs the older version no longer fails on outbox actions that a newer replica wrote. The older replica leaves those actions as they are: it does not carry them out, end them, count them in the backlog gauges or delete them after retention. Supported actions in the same lane still wait behind them, so each lane keeps its order. No operator action is needed.
