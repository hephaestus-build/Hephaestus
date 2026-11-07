---
"hephaestus": patch
---

Commit enrichment after a push no longer holds a database connection while it waits for GitHub. A commit whose enrichment cannot be stored is now enriched again on the next sync, instead of keeping its authors without the rest of its details.
