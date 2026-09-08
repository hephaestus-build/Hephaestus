---
"hephaestus": patch
---

Repository collaborator permissions now sync again. Workspaces whose repositories use collaborator permissions saw the sync abort partway with a tenancy error, leaving the permissions Hephaestus held for that repository stale until the next full resync.
