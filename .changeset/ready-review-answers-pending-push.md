---
"hephaestus": patch
---

A push or edit made while a pull request or merge request was a draft now waits for the review that starts when it is marked Ready. That extra wait ends once the oldest waiting push or edit has waited an hour, even if the Ready review has not finished. Its own review then skips the code practices the Ready review already answered on the same commit and base, and the review details list each skipped practice with a link to the review that answered it. Practices that also read the description, commits, comments, linked issues or documents are still reviewed, as are practices only a push or edit checks. When the Ready review already answered every ready practice, the push or edit review completes without running a model and shows "Answered by an earlier review" instead of an empty result.

The bundled practices "Include tests with the change", "Keep the test suite honest" and "Track generated artifacts only when justified" now declare the description and commits they already read as evidence.

Workspace administrators must adopt these three evidence updates through the practice catalogue before using review reuse with existing copies. Customized practices must declare every source their criteria read. Existing practice revisions and observations are not rewritten.
