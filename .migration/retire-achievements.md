#### 🔴 Achievements retired

Achievement pages, unlock notifications, the skill-tree designer, and achievement administration are no longer available. Remove bookmarks and external links to these routes:

- `/w/{workspaceSlug}/achievements`
- `/w/{workspaceSlug}/user/{username}/achievements`
- `/w/{workspaceSlug}/admin/achievements`
- `/w/{workspaceSlug}/admin/achievement-designer`

Remove clients of `/workspaces/{workspaceSlug}/users/{login}/achievements` and its `/definitions`, `/recalculate`, and `/reload` endpoints. Workspace responses no longer include `achievementsEnabled`; stop sending that property to the workspace feature-update endpoint. There are no replacement achievement endpoints or redirects.

Stored achievement progress and the old workspace flag are retained in the database, but the application no longer evaluates or serves achievements. No data-drop migration is included. Activity history, practice feedback, leaderboards, leagues, and XP progression are unaffected.

Retained progress is contributor-global, not workspace-owned. Workspace purge and sign-in account deletion do not erase it; operators must handle verified erasure explicitly. The [personal-data map](https://docs.hephaestus.build/admin/dsms/personal-data-map) documents the retained store.
