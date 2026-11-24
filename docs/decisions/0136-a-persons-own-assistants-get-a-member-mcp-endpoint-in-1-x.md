# ADR 0136: A person's own assistants get a member MCP endpoint in 1.x

**Status:** Proposed
**Date:** 2026-11-23
**Authors:** Felix T.J. Dietrich

**Builds on:** [ADR 0110](0110-a-workspace-admins-mcp-client-configures-practices-through-the-workspace-mcp-endpoint.md) (the workspace MCP endpoint), ADR 0135 (proposed in #4867, one API, no reserved shapes)

> **1.0 does not build this.**
>
> - A 1.x release builds the member MCP endpoint.
> - The workspace MCP endpoint of ADR 0110 stays as it is.
> - 1.0 reserves two names: the token audience of external hosts and the path for host grants.
> - 1.0 creates no route, operation, table or token for them.

## Context

Developers ask their own coding assistants about their work, in the editor or in a terminal.
These assistants speak the Model Context Protocol (MCP).
The best moment for practice feedback is often the moment of work in such a host.

Main has one MCP endpoint: `POST /api/workspaces/{slug}/mcp` ([ADR 0110](0110-a-workspace-admins-mcp-client-configures-practices-through-the-workspace-mcp-endpoint.md)).
Its audience is a workspace admin, and it configures practices.
A member's assistant is a different reader.
It runs on a device that Hephaestus does not control, and its model provider can read all that the endpoint returns.

The real constraint is that a host outside Hephaestus becomes a reader of a person's private data.

## Decision drivers

- A person's private data reaches an outside host only by the person's grant for that host.
- The person can revoke one host without signing out elsewhere.
- No public shape exists in 1.0 without a caller (ADR 0135, proposed in #4867).
- MCP hosts update on their own schedule, so the tools are a contract.

## Considered options

1. **No member endpoint.** A person copies text from the app into the assistant. The moment of work is lost, and copied text has no grant and no revoke.
2. **Widen the workspace MCP endpoint to members.** One endpoint then serves two audiences, an admin who configures and a member who reads private data. A token for one audience could reach the tools of the other.
3. **A member endpoint that returns full text by default.** Every answer leaves the instance to the host's model provider, also when a link would do.
4. **Build the member endpoint in 1.0.** It adds a token audience, grants, tools and their compatibility promise before any 1.0 client needs them.
5. **A member endpoint in 1.x with its own audience, grants for each host, and links by default (chosen).**

## Decision

Option 5. The rules below bind the 1.x design.

1. **1.0 keeps the workspace MCP endpoint as it is.** ADR 0110 stays in force for workspace admins.
2. **The member endpoint has its own token audience.** A token for the web app, the extension or the workspace endpoint is refused there, and the reverse.
3. **Each host has its own grant.** The person grants a named host, and can revoke it alone. A revoke ends that host's access at the next call.
4. **Links by default.** The `ask` tool answers with a link into the app. Text reaches the host only when the person's grant allows text for that host.
5. **A host never causes an effect.** A turn that a host starts only answers. It posts nothing, sends nothing and changes no record.
6. **A grant to learning records is a snapshot by default.** Future records reach the host only when the person ticks that option.
7. **The MCP tools are in the compatibility policy** of [ADR 0103](0103-the-public-api-grows-only-by-addition-and-every-client-tolerates-what-it-does-not-know.md). A tool grows only by addition.
8. **1.0 reserves only the names** of the external host audience and the grant path. No other concept may use them.

## Consequences

- Good: the moment of work gets practice feedback in 1.x, with no second contract for admins.
- Good: a person sees and revokes each host alone.
- Good: 1.0 carries no unused route, table or promise.
- Bad: in 1.0, a person cannot reach Hephaestus from their own assistant.
- Bad: links by default make an answer less useful inside a host that cannot open the app.
- Bad: each MCP tool becomes a promise until 2.0 when it ships.

## Confirmation

Not enforced. 1.0 has no member endpoint.
Review catches a 1.0 change that adds a member route, a member token audience or a grant table.
ADR 0110's tests keep the workspace endpoint as it is.

## Revisit trigger

- Members ask for their own assistants in shared conversations or issues more than once a month.
- The MCP specification changes how a server binds a token to its audience.

## Sources

- Model Context Protocol, [Authorization](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization). A token is bound to the MCP server that it is for. A server refuses a token for another audience. This gives decision 2.
- RFC 8707, [Resource Indicators for OAuth 2.0](https://www.rfc-editor.org/rfc/rfc8707). The client names the resource, and the token is valid only there. This gives one audience for the member endpoint.
