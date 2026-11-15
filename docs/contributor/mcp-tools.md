---
title: Add an MCP tool
description: Add a tool to the workspace MCP endpoint that maps onto one HTTP handler, returns only contract fields, and keeps the handler's authorization.
page_type: how-to
---

The workspace MCP endpoint lets an MCP client, such as Claude Code, configure a workspace for an admin.
[ADR 0110](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0110-a-workspace-admins-mcp-client-configures-practices-through-the-workspace-mcp-endpoint.md) owns the design, the tool list and the data contract.
The [admin guide](/admin/mcp-clients) tells operators how to turn it on.

A tool is a Spring bean that implements `core.mcp.McpTool`.
The MCP server registers every such bean on the server role.
The tool runs on the request thread, so the workspace context and the authentication of the request apply.

## Steps

1. **Choose the HTTP handler that the tool stands for.** A tool adds no second path: it calls that handler's service.
   Declare it on the class with `@McpToolFor(controller = …, method = "…")`.
   A handler that needs `@RequiresRecentSignIn` or the instance-admin role cannot have a tool.
2. **Put the tool in the module of the handler**, in its `mcp` package, such as `practices.mcp`.
   The module allows `core::mcp-spi` in its `package-info.java`.
3. **Put `@RequireAtLeastWorkspaceAdmin` on the `call` method.** Every tool needs the admin role, even if its handler lets any member read.
   A handler that requires more than admin, such as the owner role, cannot have a tool.
   `McpToolHandlerParityArchTest` checks both rules.
4. **Write the input and output JSON Schemas by hand** as text blocks, and build the definition with `McpTools.readTool`.
   Every object in the output schema has `"additionalProperties": false` and requires every property.
   The SDK validates each result against the output schema, so a field outside the schema fails the call.
5. **Return a projection, never a DTO.** Build records that hold only the fields of the data contract.
   Leave out text that a developer or a provider wrote: titles, bodies, comments, branch names, display names, channel and collection names, and free-text error messages.
   Send a stored reason only when it is code-shaped; `McpProjections.code` turns anything else into `UNKNOWN`.
6. **Say in the description which fields hold configuration text**, such as `criteria`, and that a client treats them as data.
7. **Add a row to the data contract** in ADR 0110 for any field of a new class, and the reverse tool for any write.
8. **Test the tool over real HTTP** in an integration test that connects a client with `McpAuthorizationFlow`, as `McpPracticeToolsIntegrationTest` does.
   Assert on what the tool returns, and on the rows that a write tool created.

## What a refusal looks like

A tool does what its name says, or it refuses and says why.
A refused role check or a `ResponseStatusException` from the service becomes a tool result with `isError: true` and the reason as its text.
Never map a request to a different effect.
