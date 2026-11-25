---
"hephaestus": patch
---

Hephaestus no longer starts questions or weekly digests after their start window has passed, including when a database migration ends. A weekly digest's cutoff stays fixed even if enqueueing takes longer than expected. An overdue digest ends even while questions are waiting.
