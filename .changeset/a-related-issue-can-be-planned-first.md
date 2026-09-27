---
"hephaestus": patch
---

The bundled "Plan the work in an issue before starting it" practice now reviews a partial merge request
or pull request that explicitly names its issue with `Related to #N`. It compares the issue's opening
time with the first commit without claiming that the partial change closes the issue. Unresolved or
excluded references leave the outcome undetermined or not applicable, and oversized linked-issue
captures are withheld rather than silently truncated. Workspaces that already adopted the practice
keep their version until an administrator accepts the offered update.
