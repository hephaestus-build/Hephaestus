---
---

Refactor with no user-facing effect: GitLab webhook handling now keeps the checks before a write, the writing of pushed commits, and the read of a selected-projects workspace's state each in their own part.
