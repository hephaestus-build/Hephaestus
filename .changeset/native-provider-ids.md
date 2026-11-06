---
"hephaestus": minor
---

Repository monitors and stopped GitLab projects now require their provider IDs. A renamed project keeps its selection, and a reused path cannot inherit an earlier stop.

The first bootstrap of a GitHub PAT workspace now requires GitHub to confirm every repository in its configuration.

**Operators:** Stop every role before the upgrade. The migration preserves all rows and fills missing IDs from provider identities that the workspace owns on its connection's origin. It names unresolved or duplicate selections and stops before this change writes data. Follow the native provider ID migration entry to repair them or remove an abandoned selection after the required checks.
