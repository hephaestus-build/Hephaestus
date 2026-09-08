#### 🔴 Achievements retired

Achievement pages, unlock notifications, the skill-tree designer, and achievement administration are no longer available. Remove bookmarks and external links to these routes:

- `/w/{workspaceSlug}/achievements`
- `/w/{workspaceSlug}/user/{username}/achievements`
- `/w/{workspaceSlug}/admin/achievements`
- `/w/{workspaceSlug}/admin/achievement-designer`

Remove clients of `/workspaces/{workspaceSlug}/users/{login}/achievements` and its `/definitions`, `/recalculate`, and `/reload` endpoints. Workspace responses no longer include `achievementsEnabled`; stop sending that property to the workspace feature-update endpoint. There are no replacement achievement endpoints or redirects.

The upgrade permanently drops `user_achievement` and `workspace.achievements_enabled`. There is no data export, retained achievement storage, or replacement feature in the application. Activity history, practice feedback, leaderboards, leagues, and XP progression remain available.

Before upgrading, back up the database and stop every application runtime role (`server`, `worker`, and `webhook`). Start only the upgraded version after migration; a rolling deployment with older versions is not supported for this removal. Returning to an older version requires restoring the pre-upgrade database backup; Liquibase cannot recover deleted progress.
