---
"hephaestus": patch
---

The webhook role no longer receives the database login, the encryption keys or the PostgreSQL CA, and its network policy no longer reaches the database. It has not used them since it stopped reading the database, so a compromised receiver now exposes less. You do not need to change any values.
