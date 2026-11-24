---
"hephaestus": minor
---

The instance database records privacy facts for erasure recovery, including when someone withdraws Slack channel-message consent. The backup job reports its oldest recovery point. When the report is missing or stale, the journal keeps every fact until a new report arrives.

**Operators:** If the chart manages your backups, rerun the release key setup with backups enabled to add the backup reporter password. See [privacy journal retention](https://docs.hephaestus.build/admin/upgrade-notes/privacy-journal).
