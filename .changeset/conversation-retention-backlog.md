---
"hephaestus": patch
---

Conversation retention now usually erases a large backlog within hours instead of over several weeks. The job runs every 15 minutes and erases up to 20,000 due entries in each run. The database clock decides which entries are due. If the erasure of one person's entries fails, the other people in the workspace still have theirs erased. The `privacy_job_completed_total` metric reports the job as `conversation_retention`. The new `conversation_retention_deadline_margin_seconds` metric and the `HephaestusConversationRetentionBehind` alert show when the oldest due entry has less than seven days left before its limit. `HephaestusConversationRetentionUnsampled` shows when reads of that metric keep failing or stop.
