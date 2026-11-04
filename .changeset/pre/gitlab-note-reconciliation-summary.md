---
"hephaestus": patch
---

When a GitLab instance cannot answer the note checks of a deletion sweep, the server now logs one warning for each project instead of one for each issue and merge request. The new `gitlab_note_reconciliation_unverified_total` metric counts the issues and merge requests that the sweep could not check. The next reconciliation checks them again.
