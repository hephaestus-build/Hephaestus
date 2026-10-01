---
"hephaestus": patch
---

A GitLab project that is renamed or moved inside the connected group keeps a single monitored repository, with its sync progress and practice review selection, even when work on the new path arrives before the rename itself or a sync finds the new path first. A workspace that already monitors a project twice is repaired the next time a sync or a project event reports that project, and the remaining entry keeps the review selection of both. Adding a GitLab project by hand no longer creates a second entry for an already synced project the workspace monitors under another path. Syncs and project events no longer add a monitored repository once the GitLab connection is disconnected, or for a project outside the connected GitLab instance and group. Adding a repository by hand to a workspace with no active GitHub or GitLab connection is refused with a request to connect one first.
