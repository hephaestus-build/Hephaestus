---
"hephaestus": minor
---

Practice reviews can inspect the captured repository and reachable Git history with native tools.
Binary assets and hidden or vendored source are no longer omitted individually. Snapshots above
`GIT_MAX_SNAPSHOT_BYTES` are refused whole. The repository is read-only, has no upstream credentials,
and supports verified historical citations.

A review now uses one model session for grouped practice checks and feedback composition. It records
valid observations independently, stages linked issues as citable text, and records per-turn calls,
tools and refusals. Practice-page feedback requires recurring negative observations across work;
a single occurrence does not create a recurring-habit card.

Bundled criteria now use a common decision-procedure format and can exclude assessed cells that do
not apply to the practice. The new **State how to verify the change** practice checks verification
guidance. Catalog updates do not rewrite workspace-adopted copies.

Private execution archives are no longer collected. Reviews retain verified citation results rather
than archives of complete inputs, model requests and session transcripts. Practice reviews always
use an internal network; internet access remains a Heph-only setting.

**Operators:** deploy matching server and agent images, remove the retired repository size limits, `SANDBOX_DOCKER_CLI`, `HEPHAESTUS_FABRIC_GC_RETENTION_DAYS` and the execution-capture setting, and review the expanded repository-history scope with your privacy owner before updating installed policies to source contract 1.2.0. Existing policy revisions are not rewritten at startup. The migration guide has the steps.
