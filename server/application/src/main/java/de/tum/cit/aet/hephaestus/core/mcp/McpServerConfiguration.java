package de.tum.cit.aet.hephaestus.core.mcp;

import de.tum.cit.aet.hephaestus.core.auth.mcp.McpCallGrant;
import de.tum.cit.aet.hephaestus.core.auth.mcp.McpGrantGuard;
import de.tum.cit.aet.hephaestus.core.auth.spi.McpClientGrants;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.ApiPath;
import io.micrometer.core.instrument.MeterRegistry;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * The workspace MCP endpoint, {@code POST /api/workspaces/{workspaceSlug}/mcp}, in stateless mode: any
 * replica answers any request, and the server never calls the client.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class McpServerConfiguration {

    static final String ENDPOINT = ApiPath.of("/workspaces/{workspaceSlug}/mcp");

    @Bean
    WebMvcStatelessServerTransport mcpTransport(
            JsonMapper jsonMapper, @Value("${hephaestus.host-url}") String hostUrl) {
        return WebMvcStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(McpTools.withNulls(jsonMapper)))
                .messageEndpoint(ENDPOINT)
                // A browser page on another origin must not drive the endpoint (DNS rebinding).
                .securityValidator(DefaultServerTransportSecurityValidator.builder()
                        .allowedOrigin(hostUrl)
                        .build())
                .build();
    }

    /**
     * Every request passes its grant check, then the write-scope check, before the MCP server sees it. The filter
     * added last runs first, so the grant guard is added last.
     */
    @Bean
    RouterFunction<ServerResponse> mcpRoutes(
            WebMvcStatelessServerTransport mcpTransport,
            McpGrantGuard grantGuard,
            List<McpTool> tools,
            JsonMapper jsonMapper) {
        return mcpTransport
                .getRouterFunction()
                .filter(new McpWriteScopeFilter(tools, jsonMapper, grantGuard))
                .filter(grantGuard);
    }

    @Bean(destroyMethod = "close")
    McpStatelessSyncServer mcpServer(
            WebMvcStatelessServerTransport mcpTransport,
            JsonMapper jsonMapper,
            List<McpTool> tools,
            ApplicationContext applicationContext,
            MeterRegistry meterRegistry,
            McpClientGrants grants) {
        McpRefusals refusals = new McpRefusals(applicationContext);
        McpToolMetrics metrics = new McpToolMetrics(meterRegistry, tools);
        Runnable recordUse =
                () -> McpCallGrant.current().ifPresent(grant -> grants.recordUse(grant.workspaceId(), grant.grantId()));
        List<McpStatelessServerFeatures.SyncToolSpecification> specifications = tools.stream()
                .map(tool -> new McpStatelessServerFeatures.SyncToolSpecification(
                        tool.definition(), (context, request) -> invoke(tool, request, refusals, metrics, recordUse)))
                .toList();
        return McpServer.sync(mcpTransport)
                .jsonMapper(new JacksonMcpJsonMapper(McpTools.withNulls(jsonMapper)))
                .serverInfo("hephaestus", "1")
                .capabilities(
                        McpSchema.ServerCapabilities.builder().tools(false).build())
                // Tools run on the request thread, where the security and workspace contexts live.
                .immediateExecution(true)
                .tools(specifications)
                .build();
    }

    /**
     * A refusal is a tool error that names its reason, never a protocol failure: the client must see why
     * the tool did not do what it asked. Only a call that succeeds records the grant's use, so a client that the
     * policy refuses does not look busy in the console.
     */
    static McpSchema.CallToolResult invoke(
            McpTool tool,
            McpSchema.CallToolRequest request,
            McpRefusals refusals,
            McpToolMetrics metrics,
            Runnable recordUse) {
        String name = tool.definition().name();
        // The write-scope filter answers the challenge; this check is the one that a write never passes without the
        // scope.
        if (tool.writes() && !McpCallGrant.current().map(McpCallGrant::writes).orElse(false)) {
            metrics.record(name, McpToolMetrics.Outcome.REFUSED);
            return error(
                    "This client may only read this workspace. Ask an admin to connect it again and allow changes.");
        }
        try {
            McpSchema.CallToolResult result = tool.call(request.arguments() == null ? Map.of() : request.arguments());
            metrics.record(name, McpToolMetrics.Outcome.OK);
            recordUse.run();
            return result;
        } catch (AccessDeniedException refused) {
            metrics.record(name, McpToolMetrics.Outcome.REFUSED);
            return error("Your role in this workspace does not allow this tool.");
        } catch (ResponseStatusException refused) {
            if (refused.getStatusCode().is5xxServerError()) {
                return failed(name, refused, metrics);
            }
            metrics.record(name, McpToolMetrics.Outcome.REFUSED);
            String reason = refused.getReason();
            return error(reason == null ? "The request was refused." : reason);
        } catch (RuntimeException failed) {
            Optional<String> refusal = refusals.clientError(controllerOf(tool), failed);
            if (refusal.isPresent()) {
                metrics.record(name, McpToolMetrics.Outcome.REFUSED);
                return error(refusal.get());
            }
            return failed(name, failed, metrics);
        }
    }

    /** A server error's message may name internals, so the client gets none of it. */
    private static McpSchema.CallToolResult failed(String name, RuntimeException failure, McpToolMetrics metrics) {
        metrics.record(name, McpToolMetrics.Outcome.FAILED);
        log.error("mcp: tool {} failed", name, failure);
        return error("The tool failed. Try again later.");
    }

    /** The controller of the handler that the tool stands for, whose advice decides how a refusal reads. */
    private static Class<?> controllerOf(McpTool tool) {
        McpToolFor handler = AnnotationUtils.findAnnotation(AopUtils.getTargetClass(tool), McpToolFor.class);
        return handler == null ? Object.class : handler.controller();
    }

    static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(message)
                .isError(true)
                .build();
    }
}
