---
"hephaestus": patch
---

Feedback is no longer posted on a pull request or merge request for a version that was already replaced. Before it posts, Hephaestus now checks the current head on GitHub or GitLab, even when it has not yet processed a push event. If the work moved on, the feedback is withheld because the work changed. If GitHub or GitLab cannot be reached, nothing is posted and Hephaestus tries again later.
