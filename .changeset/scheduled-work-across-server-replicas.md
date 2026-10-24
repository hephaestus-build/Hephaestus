---
"hephaestus": patch
---

Historical backfill and the scheduled GitLab webhook check no longer run twice when two application servers share one database, so GitLab no longer receives a duplicate webhook from that check. A long background task no longer delays the other background tasks, such as the heartbeat that keeps a running sync alive.
