---
"hephaestus": patch
---

A worker that resumes after a pause now detects an expired model-call lease when it checks a new or retried request. It cancels that request without waiting for the periodic check.
