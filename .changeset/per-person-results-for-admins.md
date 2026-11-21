---
"hephaestus": minor
---

Workspace admins see practice results with no names and no filter by person, unless a workspace owner turns on **Per-person results for admins** in Settings. Each result still shows the work it is about. Workspaces that already ran practice reviews keep per-person results; the change is recorded, and any admin can turn it off.

**API clients:** while the setting is off, `developer` is `null` on practice-page and conversation feedback in `GET /workspaces/{workspaceSlug}/practices/reviews/feedback`, `GET …/feedback/{feedbackId}`, and the `202` answers of `POST …/feedback/{feedbackId}/recheck` and `…/retry`. A filter by person is refused.
