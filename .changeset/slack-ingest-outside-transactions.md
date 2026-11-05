---
"hephaestus": patch
---

Storing a Slack message no longer holds a database connection while Hephaestus asks Slack who wrote it, including while it waits out Slack's rate limit.
