---
"hephaestus": patch
---

A practice review whose sandbox cannot prepare its workspace now runs again within the job's retry limit instead of failing at once, because no model call has run yet. The worker log names the failure and its exit code, and after the last attempt the job keeps the initializer's exit code and the last lines of its log, with credentials masked, for the operator.
