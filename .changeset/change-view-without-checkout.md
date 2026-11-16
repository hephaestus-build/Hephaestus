---
"hephaestus": patch
---

A review whose practices read the code changes but not the repository files now runs. Before, its sandbox could not show the changes without a copy of the repository, so every attempt failed and the review ended as a failed execution. This happened on the first attempt when a workspace enabled such practices without one that reads the files, for example Issue traceability and lifecycle on a merged pull request, and on a later attempt when only such practices were left. The review still copies no repository files for these practices.
