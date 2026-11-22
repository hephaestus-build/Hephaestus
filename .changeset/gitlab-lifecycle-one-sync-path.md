---
"hephaestus": patch
---

The GitLab sync that runs after you connect, and when the server starts, now runs the same steps as the scheduled sync. It checks that each project is still available and records an error on a project whose sync did not finish, as the scheduled sync already did.
A server start or a rolling deploy no longer syncs a GitLab connection again when its last successful sync finished less than `SYNC_COOLDOWN_MINUTES` minutes ago. A new connection always syncs.
The sync after you connect, and at server start, links commits to every merge request again, so a commit that arrived after its merge request was last read gets its link. When linking commits to merge requests fails, the next sync reads those merge requests again instead of skipping them.
