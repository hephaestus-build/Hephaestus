---
"hephaestus": minor
---

The webhook receiver no longer needs the database. It starts and keeps accepting GitHub, GitLab, Slack and Outline deliveries while PostgreSQL is down, for example during a restore or a database upgrade, and the server processes them when it is back. The receiver needs no database settings and no encryption keys; you can remove them from the webhook container. Each Outline subscription now posts to its own address, and its signing secret comes from the webhook routing secret. Slack channel consent reaches the receiver through NATS.

**Operators:** on each workspace's Outline connection, choose **Sync now** after the upgrade, so Hephaestus registers the Outline subscription on its new address.
