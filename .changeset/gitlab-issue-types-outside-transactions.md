---
"hephaestus": patch
---

The GitLab sync of a group's issue types no longer holds a database connection while it waits for GitLab, including while it waits out a rate limit.
