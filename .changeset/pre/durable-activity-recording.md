---
"hephaestus": patch
---

Temporary recording failures no longer silently drop GitHub or GitLab activity.
The existing retry job retries failed records every five minutes. A large backlog can take longer to clear.
If a workspace stops monitoring the source repository or its provider connection is not active, its queued activity ends without a new record.

Before upgrading, let running person-erasure requests finish. This update adds queued activity to their store inventory.
