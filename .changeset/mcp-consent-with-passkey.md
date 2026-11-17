---
"hephaestus": minor
---

A workspace admin can now connect an MCP client, such as Claude Code, to a workspace that requires an admin passkey. The consent page asks the admin to confirm with their passkey before it allows the client. The client then keeps its access until an admin disconnects it or it goes unused for 90 days, and a refresh token that is used twice ends its access at once. Workspace settings show when each client was last used and until when an unused client keeps its access. An account whose passkey recovery is pending, and an instance admin on an instance that requires a passkey from its instance admins, still cannot approve a client, and the consent page names the reason. A client approved before the workspace turned on its passkey policy stays refused until an admin approves it again with a passkey.
