---
"hephaestus": patch
---

GitLab approvals no longer inherit a merge request's update time or a webhook's arrival time. For a merged merge request, Hephaestus recovers the approval dates GitLab reports; when an approval cannot be dated reliably, Hephaestus keeps its time unknown rather than claiming it happened before the merge, and keeps the approved commit unknown when GitLab does not say which one it was.
