---
"hephaestus": patch
---

Before Hephaestus posts feedback on a pull request or merge request, it now checks the current head on GitHub or GitLab, even when it has not yet processed the push event. If the head moved past the reviewed version, the feedback is withheld because the work changed. If GitHub or GitLab cannot answer, nothing is posted and Hephaestus tries again later.
