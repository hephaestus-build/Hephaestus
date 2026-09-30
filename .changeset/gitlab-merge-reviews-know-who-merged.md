---
"hephaestus": patch
---

Practice reviews of a merged GitLab merge request now know who merged it. Hephaestus keeps the merge commit GitLab reports and reads who merged the merge request, and when, from GitLab after the merge. A practice about merging, such as merging only after approval, is reviewed when you merged your own merge request; when someone else merged, it is still not attributed to you. While Hephaestus does not yet know who merged, the review waits and shows "Who merged this is not known yet" instead of running without that practice, and it runs once Hephaestus learns who merged.
