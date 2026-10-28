---
"hephaestus": minor
---

Practice reviews can select CI evidence separately from pull request details. Checks and merge status now have their own source. Review decisions belong to the review discussion source.

**Operators:** Before reviews resume after the upgrade, update custom scripts and criteria that read these fields from `metadata.json`. Read CI fields from `checks.json` and the aggregate review decision from `review_threads.json`. The upgrade moves current practice definitions and catalog comparison bases to source contract 3.0.0. It preserves custom criteria, scripts, and historical versions.

Custom scripts that call `mergeLead` now pass the merge facts and label: `mergeLead(merge, label)`.
