#### 🔴 Update team API callers

- Change `/api/workspaces/{workspaceSlug}/team` to `/api/workspaces/{workspaceSlug}/teams`.
- Use PATCH for team and repository visibility, with a required JSON object `{ "hidden": true }` or `{ "hidden": false }`. Remove the visibility query parameter and primitive boolean body.
- Replace POST `.../teams/{teamId}/labels/{repositoryId}/{label}` with PUT `.../teams/{teamId}/labels/{labelId}`. DELETE uses the same label-id path.

Regenerate callers from the OpenAPI specification. The web app uses the new contract in this release. Repeated label additions and removals keep the same result.
