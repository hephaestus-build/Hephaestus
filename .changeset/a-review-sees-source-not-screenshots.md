---
"hephaestus": minor
---

Practice reviews can use bash, Git, ripgrep, and file-discovery tools to inspect the captured repository and its reachable history. Source and tests are no longer omitted at a snapshot size or file-count limit; binary assets and hidden or vendored source remain available. The captured repository is read-only and carries no upstream credentials. Feedback can quote a line from the repository's history as well as from the reviewed commit, and every quote is verified against the Git object before it is delivered.

Private execution archives are no longer collected. Reviews retain verified citation results rather than copies of complete inputs, model requests, and session transcripts.

**Operators:** deploy the new `git-preparation` image with matching server and agent images, remove the retired repository size limits and execution-capture settings, and review the expanded repository-history scope with your privacy owner. The migration guide has the steps.
