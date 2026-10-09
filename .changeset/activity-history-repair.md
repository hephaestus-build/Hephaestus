---
"hephaestus": patch
---

Workspace activity restores missing pull requests, merge requests, reviews, issues, and comments from stored history. Activity recording slows synchronization under load instead of dropping events. GitHub backfill checks suspicious completed scans against the provider and restarts incomplete history. Workspace administrators can run the repair through the existing backfill action. Erased people stay erased, and replies to review comments do not count as reviews.
