---
"hephaestus": minor
---

Team API routes now use `/teams`. Send visibility updates with PATCH and a required `{ "hidden": true }` or `{ "hidden": false }` JSON body. Query-only and primitive boolean requests are no longer accepted.

Add a team label with PUT and remove it with DELETE at `/api/workspaces/{workspaceSlug}/teams/{teamId}/labels/{labelId}`. The repository and label-name POST route is removed.

**Operators:** Before upgrading, update API callers to use these routes and request bodies.
