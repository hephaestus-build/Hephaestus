---
"hephaestus": patch
---

Connection audit records no longer keep the admin who connected, changed or disconnected an integration, or the reason they gave, for more than 365 days, like configuration audit events. A server job removes them every day at 00:25. The event and its time stay until the workspace is removed, so a repeated provider event still changes nothing. Before, the whole record stayed that long.
