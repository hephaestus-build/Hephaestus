---
"hephaestus": patch
---

GitLab approvals no longer inherit a merge request's update time or a webhook's arrival time. When the current approval cannot be dated reliably, Heph keeps its time unknown rather than claiming it happened before a merge.
