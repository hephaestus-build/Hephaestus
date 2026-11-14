/**
 * The workspace MCP endpoint (ADR 0110): the stateless MCP server, its route and its security chain.
 * Modules contribute tools through {@link de.tum.cit.aet.hephaestus.core.mcp.McpTool}.
 */
@org.springframework.modulith.NamedInterface("mcp-spi")
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.core.mcp;
