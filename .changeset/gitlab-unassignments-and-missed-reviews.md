---
"hephaestus": patch
---

On GitLab, removing everyone assigned to an issue or merge request, or a merge request's last reviewer, now takes effect as soon as GitLab reports it; before, the old assignees and reviewers stayed until a later sync corrected them. Each incremental sync also re-reads who is asked to review the open merge requests and where they stand, so an approval, a request for changes or a new review request whose webhook was missed still reaches **Needs you** and Heph. If that re-read cannot finish, the sync completes with a warning under the repository's sync status.
