---
"hephaestus": patch
---

A GitLab merge request whose id matches an issue's id now syncs. GitLab numbers the two separately, and until now Hephaestus dropped such a merge request while the sync still reported success. The next sync brings in any merge request that was dropped.
