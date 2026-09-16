---
"hephaestus": minor
---

Practice reviews can use bash, Git, ripgrep, and file-discovery tools to inspect the captured repository and its reachable history. Source and tests are no longer omitted at a snapshot size or file-count limit; binary assets and hidden or vendored source remain available. The captured repository is read-only and carries no upstream credentials. Feedback can quote a line from the repository's history as well as from the reviewed commit, and every quote is verified against the Git object before it is delivered.

A practice review is one model session: the captured context is read once, the practices go to the model a group at a time, several observations or pieces of feedback can be recorded in one call, and feedback is composed in the same session from what was admitted. A review needs far fewer model calls and tokens than before. A quote of the change copied with its diff marker is now recorded as the line's content, so it is verified as the same evidence it was at review time.

Private execution archives are no longer collected. Reviews retain verified citation results rather than copies of complete inputs, model requests, and session transcripts.

Internet access is a Heph setting only: practice reviews always run on an internal network, so the switch no longer appears for them and a request that turns it on for practice reviews is rejected.

**Operators:** deploy matching server and agent images, remove the retired repository size limits, `SANDBOX_DOCKER_CLI`, `HEPHAESTUS_FABRIC_GC_RETENTION_DAYS` and the execution-capture setting, and review the expanded repository-history scope with your privacy owner before updating installed policies to source contract 1.2.0. Existing policy revisions are not rewritten at startup. The migration guide has the steps.
