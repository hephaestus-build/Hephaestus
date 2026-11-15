---
"hephaestus": minor
---

The server now refuses to start without its system encryption key and its credential encryption key in every profile, not only in production, and names the missing setting. A deployment through the chart already sets both keys and needs no change. A server started without the `prod` profile must set `HEPHAESTUS_SECURITY_ENCRYPTION_KEY` and `HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY`: the `e2e` profile no longer supplies default keys. For contributors, local setup still writes both keys for a new database, and it now stops with a clear message when an existing local database has no key.
