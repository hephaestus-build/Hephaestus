---
"hephaestus": patch
---

A practice review whose sandbox cannot prepare its workspace now runs again within the job's retry limit instead of failing at once, because no model call has run yet. The worker log names the failure and its exit code, and after the last attempt the job keeps the initializer's exit code and the last lines of its log, with known credential formats masked, for the operator. A review that fails before its sandbox starts now also logs the stage, the cause and the exception types, and a failed repository operation is named as such instead of as an unclassified cause.
