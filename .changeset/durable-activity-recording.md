---
"hephaestus": patch
---

Temporary recording failures no longer silently drop GitHub or GitLab activity.
Failed records are retried automatically within minutes.
If a workspace stops monitoring the source repository, its queued activity ends without a new record.
