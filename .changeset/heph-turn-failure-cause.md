---
"hephaestus": patch
---

A failed Heph reply now records why it failed on the turn: the Heph host's reason, or the kind and status of its last failed model call. The cause stays after the worker restarts, and each failed model call is logged as a warning with its turn.
