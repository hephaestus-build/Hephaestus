---
"hephaestus": patch
---

A worker no longer claims practice reviews its sandbox cannot start. When a host allows fewer
containers than the worker's review capacity, the worker could pick up a review, fail to start it and
put it back, logging a warning each time; a queued review waited behind that churn instead of running.
A claim interrupted by a database error no longer retires one of that worker's review slots until
the next restart.
