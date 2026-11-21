---
"hephaestus": minor
---

Workspace admins can save a practice folder to try before anyone relies on it. A slug that the workspace lacks becomes a draft practice, which no review uses and members never see. For a practice in use, the folder becomes a version beside the current one; a trial can run it while reviews keep using the current version, and the version history leaves it out. A trial can now name the version it tries. **Activate** puts a draft practice in use in feedback mode Off, and deleting a draft also deletes its trials. The API is `/api/workspaces/{slug}/practice-drafts`.
