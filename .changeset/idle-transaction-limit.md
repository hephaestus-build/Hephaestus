---
"hephaestus": patch
---

The server's database sessions now end a transaction that has been idle for 60 seconds, so a stuck request can no longer hold its locks and a connection indefinitely. A background task whose session ends this way is retried, and a request fails with an error; neither is recorded as a result about anyone's work. An `options` parameter in `DATABASE_URL` replaces this limit and the 10-second lock wait limit.
