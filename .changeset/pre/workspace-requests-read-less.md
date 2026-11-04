---
"hephaestus": patch
---

Each workspace request reads less from the database. The server now finds the signed-in person's GitHub and GitLab accounts in one read. Request logs no longer carry `context.installation_id`. Sync logs still carry it.
