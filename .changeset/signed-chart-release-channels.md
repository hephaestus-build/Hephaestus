---
"hephaestus": patch
---

Release channels can now apply a verified signed Helm chart. The installed reconciler checks the chart digest, source, and image pins before it applies the release. It can adopt the next release's tooling after the Compose files are removed. An interrupted tooling update resumes on the next tick.

For a major upgrade, stop the writers with the source chart first. The reconciler starts the target server with workers stopped and does not roll back a failed migration. Follow the stopped upgrade and database recovery procedures.

**Operators:** before promoting 1.0, install the verified 0.95.1 reconciler and its units from `deploy/reconcile/`. Follow [Pull-based deployment](https://docs.hephaestus.build/admin/pull-based-deployment#follow-a-channel-with-the-chart) for the one-time tooling update.
