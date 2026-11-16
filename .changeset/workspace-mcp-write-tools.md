---
"hephaestus": minor
---

A connected MCP client, such as Claude Code, can now also change a workspace's practice configuration when its admin allows it: add practices from the catalog, create, change, reset and remove practices, set feedback modes and practice groups, choose the repositories and people that Hephaestus reviews, and pause or resume feedback. The consent page shows the extra scope, a client that the admin allowed only to read asks again before its first change, and each change is recorded under the admin's account, and its audit record keeps which client made it. A tool that cannot do what it was asked now gives the same reason as the web app, for example a changed version or an unknown practice. Operators get the alert `HephaestusMcpToolsFailing` and the metrics `mcp_tool_calls_total` and `mcp_auth_refusals_total`.
