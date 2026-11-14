---
"hephaestus": patch
---

A connection, conversation feedback item or Slack thread that fails no longer stops the hourly expiry of the others, or keeps other settled Slack threads from review. The server log records the failed row as `scheduled.row.failed`, and the next run tries it again.
