---
"hephaestus": patch
---

A host on pull-based deployment no longer fills its disk with one checkout per release or commit it has applied. After each successful apply, it removes the release checkouts and locks it no longer needs. It keeps the release it runs, the one before it for a rollback, and the tooling it runs. It also keeps any checkout that you locked with `git worktree lock` or that holds files of its own, such as an `acme.json` from before certificates moved to the `proxy_letsencrypt` volume. Docker volumes are never touched, so a rollback creates its checkout again without issuing certificates again. The first prune happens at the second apply after the upgrade, when the host runs this version's reconciler.
