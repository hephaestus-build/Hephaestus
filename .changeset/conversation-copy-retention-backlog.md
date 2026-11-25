---
"hephaestus": patch
---

Conversation tool results that can copy Slack messages are now erased by the same job as conversation entries, every 15 minutes, so a large backlog usually goes within hours instead of over several weeks. The database clock decides which results are due, and a person whose erasure fails no longer holds back the rest of the workspace. The `rule` label of the conversation retention metrics and alerts tells `entries` from `copies`, and the `privacy_job_completed_total` metric reports the new job as `conversation_copy_retention`.
