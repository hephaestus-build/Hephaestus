package de.tum.cit.aet.hephaestus.core.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The workspace MCP endpoint (ADR 0110). Operator guidance: {@code docs/admin/mcp-clients.mdx}.
 *
 * @param enabled Lets workspace admins connect MCP clients, such as Claude Code, to their workspaces. When
 *     it is off, the MCP endpoints, their metadata and the MCP authorization endpoints answer 404.
 */
@ConfigurationProperties(prefix = "hephaestus.mcp")
public record McpProperties(@DefaultValue("false") boolean enabled) {}
