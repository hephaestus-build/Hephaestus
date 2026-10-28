---
"hephaestus": patch
---

The Helm chart now ranks its Pods: the roles, PostgreSQL and NATS first, then Hephaestus's sandboxes, then review sandboxes. When a node runs short of memory, Kubernetes now stops a review, which runs again, before it touches Hephaestus or the database. The chart creates three PriorityClasses for this, so the account that installs or upgrades the chart needs permission to create PriorityClasses. On a shared cluster without that permission, set `priorityClasses.create=false` and name three existing classes.
