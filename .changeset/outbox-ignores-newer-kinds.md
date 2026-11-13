---
"hephaestus": patch
---

During a rolling update, a replica that runs the older version no longer fails on outbox actions that a newer replica wrote. This covers new kinds of action and new variants of an existing kind, such as an email of a new notice or a new send action for a feedback. The older replica leaves those actions as they are: it does not carry them out, end them or count them in the backlog gauges. Supported actions in the same lane still wait behind them, so each lane keeps its order. No operator action is needed.
