---
"hephaestus": minor
---

Practice reviews can use bash, Git, ripgrep, and file-discovery tools to inspect the captured repository and its reachable history. Source and tests are no longer omitted at a snapshot size or file-count limit; binary assets and hidden or vendored source remain available. The captured repository is read-only and carries no upstream credentials. Feedback can quote a line from the repository's history as well as from the reviewed commit, and every quote is verified against the Git object before it is delivered.

A practice review is one model session: the captured context is read once, the practices go to the model a group at a time, several observations or pieces of feedback can be recorded in one call, and feedback is composed in the same session from what was admitted. A review needs far fewer model calls and tokens than before, and the review instructions no longer steer the model toward negative observations: a practice's own criteria decide the outcome, and a positive outcome is as ordinary as a negative one. Each linked issue is staged as text, so a review can cite it line by line. A citation is recorded as the artifact's own bytes whatever the model's reading of markers, spacing or JSON escapes, and a quote at miscounted lines is recorded where the text is. A practice-page card is written only when the practice is negative on several pieces of work; a single occurrence stays a note on the work. The job record keeps a per-turn trace of calls, tools, and refused observations with their reasons.

Every bundled practice is now written as a decision procedure — the behaviour it judges, when it does not apply, its sources, the cells it can land in, severity, grounding, and what belongs to a sibling — under one size bound, and the administrator guide states the shape for practices you write yourself. A practice that judges an undesirable behaviour names the cells it has no case for, and a review refuses an observation in one of them, so the absence of a leftover or an insecure default is recorded as the positive result it is. A new practice, "State how to verify the change", judges whether a reviewer can find where to start, what to do and what to expect. Installed workspaces see the updated entries in the catalog; adopted copies are not rewritten.

A review keeps every observation a model call carries that stands on its own: one over-long summary or one mis-named artifact no longer discards the others sent with it. The pull request record a review reads now carries the created, closed and merged moments the provider recorded.

Private execution archives are no longer collected. Reviews retain verified citation results rather than copies of complete inputs, model requests, and session transcripts.

Internet access is a Heph setting only: practice reviews always run on an internal network, so the switch no longer appears for them and a request that turns it on for practice reviews is rejected.

**Operators:** deploy matching server and agent images, remove the retired repository size limits, `SANDBOX_DOCKER_CLI`, `HEPHAESTUS_FABRIC_GC_RETENTION_DAYS` and the execution-capture setting, and review the expanded repository-history scope with your privacy owner before updating installed policies to source contract 1.2.0. Existing policy revisions are not rewritten at startup. The migration guide has the steps.
