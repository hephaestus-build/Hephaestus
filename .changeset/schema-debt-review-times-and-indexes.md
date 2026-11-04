---
"hephaestus": patch
---

Pull request review times are now stored with their time zone, like every other time Hephaestus stores. Erasing a person or deleting an issue or a review comment no longer scans whole tables to check what still refers to it. The upgrade rewrites the pull request review table once and removes an unused index from the commit file table. It builds six of the new indexes without blocking writes. The two indexes on the sign-in audit log briefly block writes to that log while they build; the log is small.
