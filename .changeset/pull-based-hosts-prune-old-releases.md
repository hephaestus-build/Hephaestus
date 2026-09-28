---
"hephaestus": patch
---

A host on pull-based deployment no longer fills its disk with a checkout for every release or commit it has applied. After each successful apply, it removes the checkouts and release locks it no longer needs. It keeps the release it runs, the one before it for a rollback, the tooling it runs, and any checkout that is locked with `git worktree lock` or that holds files of its own, such as an older proxy's `acme.json`. Docker volumes, and the certificates in them, are never touched. Pruning starts with the second apply after this upgrade, because that is the first apply the new reconciler makes.
