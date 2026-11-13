---
"hephaestus": patch
---

Inbox lease renewals no longer produce false handler-error logs. A competing write retries without spending the delivery's failure budget, while deliveries for other workspaces continue.
