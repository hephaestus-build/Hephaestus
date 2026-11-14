---
"hephaestus": patch
---

The server's database sessions now end a transaction that has been idle for 60 seconds, so a stuck request or task can no longer hold its locks and a connection indefinitely. The work stops with an error that names the cause: a provider event is retried a few times and then kept as a dead letter that you can replay, and a person-data erasure step stops until you resume it. None of this is recorded as a result about anyone's work. An `options` parameter in `DATABASE_URL` replaces this limit and the 10-second lock wait limit.
