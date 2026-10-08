---
"hephaestus": patch
---

Worker containers now retry pending person-erasure and workspace-purge requests for the review evidence they hold, including when the server role runs separately. They also periodically remove ended-attempt evidence after the existing one-hour retention period.
