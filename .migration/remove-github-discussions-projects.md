#### 🔴 GitHub Discussions and Projects V2 are removed

Hephaestus no longer synchronizes GitHub Discussions or Projects V2.
The upgrade drops their nine mirror tables and removes their activity entries.
It also removes the Discussions sync cursor and timestamps and the repository capability flag.
GitLab merge request threads and GitLab projects (repositories) remain supported.

Before upgrade:

1. Stop all Hephaestus runtime roles.
2. Back up PostgreSQL and verify a restore. Follow [Backup and restore](https://docs.hephaestus.build/admin/backup-restore).
3. Remove `HEPHAESTUS_SYNC_DISCUSSIONS_ENABLED`, `HEPHAESTUS_SYNC_PROJECTS_ENABLED`, `hephaestus.sync.discussions`, `hephaestus.sync.projects`, and `hephaestus.sync.filters.allowed-projects` from deployment configuration.
4. Remove the GitHub `discussions` and `organization_projects` permissions, the classic token scope `read:project`, and the `discussion`, `discussion_comment`, `projects_v2`, `projects_v2_item`, and `projects_v2_status_update` event subscriptions. Remove organization webhooks used only for those events.
5. Upgrade all runtime roles together, then start them.

The migration does not copy or retain the removed content.
A schema rollback cannot recreate it. To recover it, stop all roles, restore the verified pre-upgrade database backup, and start the previous release against that database.
