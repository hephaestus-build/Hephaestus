---
"hephaestus": patch
---

Bundled PostgreSQL and its backup containers now reserve their full existing memory limits. The scheduler accounts for their maximum memory before admitting more work. CPU can still burst, and database settings and connection pools stay the same.
