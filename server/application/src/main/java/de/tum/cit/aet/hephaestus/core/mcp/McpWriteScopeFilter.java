package de.tum.cit.aet.hephaestus.core.mcp;

import de.tum.cit.aet.hephaestus.core.auth.mcp.McpCallGrant;
import de.tum.cit.aet.hephaestus.core.auth.mcp.McpGrantGuard;
import de.tum.cit.aet.hephaestus.core.auth.mcp.McpScope;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers a call of a write tool under a grant without {@code practices:write} with 403 {@code insufficient_scope}
 * before the MCP server runs it, so the client can ask the admin for that scope (ADR 0110 decision 14). It runs after
 * {@link McpGrantGuard}, which leaves the checked grant on the request.
 */
final class McpWriteScopeFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    private final Set<String> writeTools;
    private final JsonMapper jsonMapper;
    private final McpGrantGuard grantGuard;

    McpWriteScopeFilter(List<McpTool> tools, JsonMapper jsonMapper, McpGrantGuard grantGuard) {
        this.writeTools = tools.stream()
                .filter(McpTool::writes)
                .map(tool -> tool.definition().name())
                .collect(Collectors.toUnmodifiableSet());
        this.jsonMapper = jsonMapper;
        this.grantGuard = grantGuard;
    }

    @Override
    public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next) throws Exception {
        boolean writes = request.attribute(McpCallGrant.ATTRIBUTE)
                .filter(McpCallGrant.class::isInstance)
                .map(McpCallGrant.class::cast)
                .map(McpCallGrant::writes)
                .orElse(false);
        if (writes || writeTools.isEmpty()) {
            return next.handle(request);
        }
        byte[] body = request.body(byte[].class);
        if (callsAWriteTool(body)) {
            return grantGuard.insufficientScope(request.pathVariable("workspaceSlug"), McpScope.WRITE);
        }
        // The body is read once; the MCP server reads this copy of it.
        return next.handle(ServerRequest.from(request).body(body).build());
    }

    /** A JSON-RPC message, or a batch of them, that calls a write tool. A body that is not JSON goes on to the server's own refusal. */
    private boolean callsAWriteTool(byte[] body) {
        JsonNode message;
        try {
            message = jsonMapper.readTree(body);
        } catch (JacksonException malformed) {
            return false;
        }
        if (message.isArray()) {
            for (JsonNode element : message) {
                if (callsAWriteTool(element)) {
                    return true;
                }
            }
            return false;
        }
        return callsAWriteTool(message);
    }

    private boolean callsAWriteTool(JsonNode message) {
        return "tools/call".equals(message.path("method").asString(""))
                && writeTools.contains(message.path("params").path("name").asString(""));
    }
}
