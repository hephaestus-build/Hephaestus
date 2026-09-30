---
"hephaestus": patch
---

Hephaestus now refreshes a GitLab merge request's readiness — its pipeline, merge status and approvals — after activity on it: opening, updating or pushing, and approving or withdrawing an approval. Newer pipeline and approval information is kept when an older notification or read arrives late, and a push that resets approvals no longer leaves the merge request reported as approved. The readiness Heph reads tells a commit GitLab reports without a pipeline apart from a skipped pipeline and from checks Hephaestus could not read.

For GitHub and GitLab alike, what Heph reads for merge advice now also includes your merge request's description and the text of the issues the provider records it as closing, both shortened when long, so an open condition can inform the next step. A closing link does not mean the issue's conditions are met.
