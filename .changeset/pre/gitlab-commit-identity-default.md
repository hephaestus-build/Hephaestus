---
"hephaestus": patch
---

Commit link correction now runs by default for GitHub and GitLab. Existing queued emails are checked against the provider's current account links, and each correction updates commit Activity in the same transaction. Renamed GitLab accounts keep their identity when another account reuses an old username. You can still pause the job with `config.commitIdentityResolutionEnabled: false`.
