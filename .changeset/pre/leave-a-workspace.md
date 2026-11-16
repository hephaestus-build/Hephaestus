---
"hephaestus": minor
---

You can leave a workspace under **User settings → Workspaces**. Sync with GitHub or GitLab does not bring you back; a workspace admin can, with **Add back to workspace** in your row under **Members**. The last owner of a workspace cannot leave it. When a removal, a demotion or a leave would leave a workspace without an owner, Hephaestus says so in plain words, and API clients get the problem type `workspace-owner-required`.
