---
"hephaestus": patch
---

Workspace activity restores missing pull requests, merge requests, reviews, issues, and comments from stored history. Activity recording slows synchronization under load instead of dropping events. GitHub and GitLab backfill check suspicious completed scans against the provider and restart incomplete history once per unresolved gap. Dismissed reviews keep review credit. A repository failure reports a warning without stopping repair of other repositories. Workspace administrators can run the repair through the existing backfill action. Erased people stay erased, and replies to review comments do not count as reviews.
