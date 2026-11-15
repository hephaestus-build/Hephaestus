---
"hephaestus": minor
---

Workspace admins can connect an MCP client, such as Claude Code, to a workspace and let it read the workspace's practice configuration: practices, practice groups, review settings, monitored repositories, members' provider logins and review status. The client never sees source code, observations, feedback or conversations. Admins see each connected client in the workspace settings and can disconnect it there. The endpoint stays off until an operator sets the new optional `HEPHAESTUS_MCP_ENABLED=true` (Helm: `config.mcpEnabled`).
