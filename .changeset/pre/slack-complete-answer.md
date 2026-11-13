---
"hephaestus": minor
---

Heph posts its complete answer in Slack. Direct messages and thread replies arrive as one message. The app keeps showing the answer as Hephaestus writes it. You can stop an unfinished answer in the app.

**Operators:** Stop all server replicas and keep a verified pre-upgrade database backup before upgrading. The migration removes the old Slack delivery and stream fields. Replies already in flight during the cutover can remain unconfirmed; their full answers remain in the app.
