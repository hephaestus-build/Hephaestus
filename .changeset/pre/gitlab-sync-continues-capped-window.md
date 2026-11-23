---
"hephaestus": patch
---

A GitLab project with more changed issues or merge requests than one sync run reads, for example on the first sync of a large project, now catches up over the next runs. Before, such a first sync marked the project as synced up to that moment, and later syncs never read the issues and merge requests it had not reached. A later sync with that many changes read the same newest pages on every run.

After the upgrade, the first sync of each GitLab project reads again the changes since its last complete sync, once.
