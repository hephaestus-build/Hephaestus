---
"hephaestus": minor
---

Practice reviews can use bash, Git, ripgrep, and file-discovery tools to inspect the captured repository and its reachable history. Source and tests are no longer omitted at a snapshot size or file-count limit; binary assets and hidden or vendored source remain available. The captured repository is read-only and carries no upstream credentials. Feedback can quote a line from the repository's history as well as from the reviewed commit, and every quote is verified against the Git object before it is delivered.

Private execution archives are no longer collected. Reviews retain verified citation results rather than copies of complete inputs, model requests, and session transcripts.

Internet access is a Heph setting only: practice reviews always run on an internal network, so the switch no longer appears for them and a request that turns it on for practice reviews is rejected.

**Operators:** deploy the new `git-preparation` image with matching server and agent images, remove the retired repository size limits, `SANDBOX_DOCKER_CLI`, `HEPHAESTUS_FABRIC_GC_RETENTION_DAYS` and the execution-capture setting, and review the expanded repository-history scope with your privacy owner. The migration guide has the steps.
