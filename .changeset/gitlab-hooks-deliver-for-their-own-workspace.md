---
"hephaestus": minor
---

Each GitLab workspace now gets its own group webhook, which delivers only for that workspace. Events from nested subgroups, from projects created in or moved into the group, and group membership changes are picked up without waiting for the next sync, and a workspace connected to a subgroup no longer depends on how another workspace's group is named. What such a webhook reports is checked with GitLab before it is stored: projects, subgroups, user profiles and memberships are recorded as GitLab reports them to the workspace's own connection, so a webhook cannot rename, move or delete another workspace's project or subgroup, change a user's profile, or grant or remove access that GitLab does not show. A project moved out of the group, deleted, or no longer visible to the connection stops being monitored by that workspace, and a deleted subgroup's team is removed by the next scheduled sync. The group webhook registered by earlier versions is left in place and keeps working as before; delete it on GitLab once the new one shows up.

**Operators:** set `WEBHOOK_ROUTING_SECRET` on the application server and the webhook receiver before upgrading; see the migration guide.
