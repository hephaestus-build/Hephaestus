package de.tum.cit.aet.hephaestus.core.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;

/**
 * One tool of the workspace MCP endpoint. It runs on the request thread, under the request's workspace
 * context and authentication, so the method security of the HTTP handler it stands for applies to it.
 */
public interface McpTool {

    /** The tool's name, description and input and output schemas. */
    McpSchema.Tool definition();

    /** Whether the tool changes the workspace, so that a call needs {@code practices:write} (ADR 0110 decision 14). */
    default boolean writes() {
        return false;
    }

    /** Runs the tool. The arguments have passed the input schema. */
    McpSchema.CallToolResult call(Map<String, Object> arguments);
}
