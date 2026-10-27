---
"hephaestus": patch
---

A practice review no longer records a result that says something is missing from the repository, such as an error handler, unless the review searched the whole repository. Before, such a claim could reach feedback when the review had read only the changed file. The check reads a short list of usual wordings, so a claim in other words can still pass. A search of a repository that was captured only in part does not count.
