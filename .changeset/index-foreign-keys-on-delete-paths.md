---
"hephaestus": patch
---

Deleting or merging a repository, a person, a milestone or a label no longer scans whole tables to check what still refers to it. The upgrade builds 12 indexes for this without blocking writes. On a database of 700,000 activity rows, the largest index takes under a second to build.
