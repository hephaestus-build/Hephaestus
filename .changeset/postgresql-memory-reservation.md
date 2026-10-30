---
"hephaestus": patch
---

Bundled PostgreSQL and its backup containers now reserve their full existing memory limits. The scheduler accounts for their maximum memory before admitting more work. CPU can still burst, and database settings and connection pools stay the same.

The PostgreSQL Pod restarts once on upgrade to apply the new request. The default database request rises from 512 MiB to 2 GiB. Dev and preview requests rise from 256 MiB to their existing 1 GiB limits. With backups enabled, the resident backup container and each backup, stanza, or restore Job request 512 MiB instead of 128 MiB.
