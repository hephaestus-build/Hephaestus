---
"hephaestus": patch
---

An MCP client's "last used" time in workspace settings now changes only when the client signs in, refreshes its access, or makes a tool call that succeeds. A call that the passkey policy, the client's role, or the tool refuses no longer counts as use, so it no longer keeps an unused client's access alive.
