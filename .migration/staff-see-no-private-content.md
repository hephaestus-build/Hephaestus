#### 🔴 Move scripts off the routes that showed staff one person

- **When:** before the window
- **Action:** Move scripts off the removed View as user, person-data export and per-person results routes, and remove the two user-view rate-limit overrides.

This release removes View as user, the download of a person's data and the workspace setting **Per-person results for admins** ([ADR 0164](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0164-staff-see-no-private-content-and-support-starts-with-the-person.md)).
The upgrade rolls. The migration keeps the column of the setting for this release, so a server of the previous release still reads its workspaces.
For each workspace that had the setting on, the migration writes one configuration audit entry.

These routes are removed:

- `GET /api/workspaces/{slug}/user-view/users` and `GET /api/workspaces/{slug}/user-view/users/{accountId}`.
- `POST /api/admin/person-data/{id}/export`. A later release delivers the records of an access request to the person's own data exports instead.
- `GET` and `PATCH /api/workspaces/{slug}/per-person-results`.

A request that carries `X-User-View-Workspace` or `X-User-View-Account` gets 403.
The Practice reviews routes return no person of a result, a problem or a review, and they ignore a `personId` filter.
A review no longer has `requestedBy`. A disagreement's `comment` is `null` 30 days after the person wrote it.
The server no longer reads `HEPHAESTUS_AUTH_RATE_LIMIT_USER_VIEW_CAPACITY` and `HEPHAESTUS_AUTH_RATE_LIMIT_USER_VIEW_PERIOD`. Remove them from your values.
Earlier `USER_VIEW` audit entries stay.
