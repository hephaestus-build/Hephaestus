---
"hephaestus": patch
---

Hephaestus no longer starts questions or weekly digests after their start window has passed, including when a database migration ends. A weekly digest's cutoff follows local daylight saving time and stays fixed even if enqueueing takes longer than expected. Digests queued by earlier replicas during a rolling upgrade keep their scheduled cutoff. An overdue digest ends even while questions are waiting.

Questions and weekly digests get their full work time after a sandbox leases them. PostgreSQL time also determines whether a weekly digest can start.
