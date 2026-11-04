---
"hephaestus": patch
---

When GitHub refuses a workspace's personal access token, that workspace's sync now pauses until you replace the token. Before, every sync kept calling GitHub and logged an error for each call. Workspace admins receive one connection alert, and the GitHub token settings say that GitHub no longer accepts the token. Sync resumes by itself after you replace it.
