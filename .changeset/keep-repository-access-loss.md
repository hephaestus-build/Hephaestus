---
"hephaestus": patch
---

A delayed GitHub or GitLab response can no longer mark a repository available again after a newer check finds that access is gone. Public activity waits for a fresh confirmation of access.

Private repositories on different provider instances can refresh at the same time, even when they share a native ID. Public activity measures confirmation age with the database clock.

Public activity requires a public confirmation from the workspace's own GitHub or GitLab connection. A private answer closes public activity in every workspace that monitors the repository. A delayed answer cannot restore an old path after a newer rename.

After the upgrade, each repository returns to its workspace's public page when the next metadata sync confirms that it is public.
