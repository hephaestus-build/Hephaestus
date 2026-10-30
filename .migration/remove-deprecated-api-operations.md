#### 🔴 Update API clients that call the removed operations

This release removes the 21 API operations that 0.95.0 deprecated.
A request to a removed operation now fails.
Stored data does not change.

Before you upgrade, find each removed operation in your API clients and scripts.
Replace it with the operation in the second column.
Paths are relative to the API base URL.

| Removed operation | Use instead |
| --- | --- |
| `renameSlug` (`PATCH /workspaces/{workspaceSlug}/slug`) | None. Old workspace addresses keep redirecting to the current one. |
| `getTeamSettings`, `getLabelFilters`, `getRepositorySettings` | `getAllTeams` (`GET /workspaces/{workspaceSlug}/team`) |
| `updateTeamSettings` | `updateTeamVisibility` (`POST /workspaces/{workspaceSlug}/team/{id}/visibility`) |
| `addLabelFilter` | `addLabelToTeam` (`POST /workspaces/{workspaceSlug}/teams/{teamId}/labels/{repositoryId}/{label}`) |
| `removeLabelFilter` | `removeLabelFromTeam` (`DELETE /workspaces/{workspaceSlug}/teams/{teamId}/labels/{labelId}`) |
| `updateRepositorySettings` | `updateRepositoryVisibility` (`POST /workspaces/{workspaceSlug}/team/{teamId}/repositories/{repositoryId}/visibility`) |
| `getMembership` | `listMemberships` (`GET /workspaces/{workspaceSlug}/members`), or `getOwnMembership` (`GET /workspaces/{workspaceSlug}/members/me`) for your own membership |
| `reorderPractices` | None |
| `getReviewBatch` | `listReviewBatches` (`GET /workspaces/{workspaceSlug}/practices/review-batches`) |
| `getReviewEvidenceOutcomes` | None |
| `getReviewUsage` | The `usage` field of `getReview` (`GET /workspaces/{workspaceSlug}/practices/reviews/{reviewId}`) |
| `listReviewPracticeScripts` | The `practices[].script` field of `getReview` |
| `getReviewItem` | The `item` field of `getItemFacts` (`GET /workspaces/{workspaceSlug}/practices/items/{itemId}/facts`) or `getItemTrace` |
| `listItemEvents` | The `events` field of `getItemTrace` (`GET /workspaces/{workspaceSlug}/practices/items/{itemId}/trace`) |
| `getOutlineCollection` | `listOutlineCollections` (`GET /workspaces/{workspaceSlug}/outline/collections`) |
| `adminGetLlmConnection`, `adminGetLlmModel` | `adminListLlmConnections` (`GET /admin/llm/connections`), `adminListLlmModels` (`GET /admin/llm/models`) |
| `workspaceGetLlmConnection`, `workspaceGetLlmModel` | `workspaceListLlmConnections` (`GET /workspaces/{workspaceSlug}/llm/connections`), `workspaceListLlmModels` (`GET /workspaces/{workspaceSlug}/llm/models`) |
