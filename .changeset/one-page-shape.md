---
"hephaestus": minor
---

The HTTP API has two page shapes. A list that a person pages through returns `{content, page, size, totalElements, totalPages}`. A long append-only list returns `{content, nextCursor}`. The audit trails and dead letters now read forward with a cursor, so a change that arrives while you load more no longer repeats a row.

**Operators:** Before you upgrade, update each script or integration that reads a paged API list. See the migration guide for what changes on which endpoint. The web app and the browser extension need no change.
