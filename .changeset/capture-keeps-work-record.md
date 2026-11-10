---
"hephaestus": patch
---

A review that needs more than one attempt now finishes. Before this fix, a retry whose remaining practices read only the code, such as the diff and the repository tree, could not prepare: it captured no record of the pull request or issue, so every later attempt failed and the review stayed open. Each capture now always reads the work's record, whatever its practices declare.
