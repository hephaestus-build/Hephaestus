---
"hephaestus": minor
---

Ship privacy facts to an optional S3-compatible journal bucket before confirming the change. The journal uses the PostgreSQL backup bucket by default when chart backups are on. Set a separate journal credential and grant access only to the journal prefix. An erasure puts the person's data on hold until the journal copy is confirmed.

**Operators:** If you use the journal bucket, create its credential Secret and use the stopped upgrade procedure.
