---
"hephaestus": patch
---

When an issue on GitHub is marked as blocked by another issue, or unmarked, Hephaestus now records the change as soon as the `issue_dependencies` webhook arrives. Before, it waited for the scheduled sync. A sub-issue or blocking issue in another repository is no longer stored in the wrong repository. Before, it could overwrite the issue with the same number there. The GitHub guide now lists every fine-grained token permission that Hephaestus uses, and what stays empty without each one.
