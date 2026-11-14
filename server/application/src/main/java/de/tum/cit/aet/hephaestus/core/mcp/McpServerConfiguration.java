package de.tum.cit.aet.hephaestus.core.mcp;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityHeaders;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStatelessServerTransport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * The workspace MCP endpoint, {@code POST /workspaces/{workspaceSlug}/mcp}, in stateless mode: any
 * replica answers any request, and the server never calls the client.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
class McpServerConfiguration {

    static final String ENDPOINT = "/workspaces/{workspaceSlug}/mcp";

    @Bean
    WebMvcStatelessServerTransport mcpTransport(JsonMapper jsonMapper, @Value("${hephaestus.host-url}") String hostUrl) {
        return WebMvcStatelessServerTransport.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .messageEndpoint(ENDPOINT)
                // A browser page on another origin must not drive the endpoint (DNS rebinding).
                .securityValidator(DefaultServerTransportSecurityValidator.builder()
                        .allowedOrigin(hostUrl)
                        .build())
                .build();
    }

    @Bean
    RouterFunction<ServerResponse> mcpRoutes(WebMvcStatelessServerTransport mcpTransport) {
        return mcpTransport.getRouterFunction();
    }

    @Bean(destroyMethod = "close")
    McpStatelessSyncServer mcpServer(WebMvcStatelessServerTransport mcpTransport, JsonMapper jsonMapper, List<McpTool> tools) {
        List<McpStatelessServerFeatures.SyncToolSpecification> specifications = tools.stream()
                .map(tool -> new McpStatelessServerFeatures.SyncToolSpecification(
                        tool.definition(),
                        (context, request) -> invoke(tool, request)))
                .toList();
        return McpServer.sync(mcpTransport)
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .serverInfo("hephaestus", "1")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                // Tools run on the request thread, where the security and workspace contexts live.
                .immediateExecution(true)
                .tools(specifications)
                .build();
    }

    /**
     * A refusal is a tool error that names its reason, never a protocol failure: the client must see why
     * the tool did not do what it asked.
     */
    static McpSchema.CallToolResult invoke(McpTool tool, McpSchema.CallToolRequest request) {
        try {
            return tool.call(request.arguments() == null ? Map.of() : request.arguments());
        } catch (AccessDeniedException refused) {
            return error("Your role in this workspace does not allow this tool.");
        } catch (ResponseStatusException refused) {
            String reason = refused.getReason();
            return error(reason == null ? "The request was refused." : reason);
        }
    }

    static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    /**
     * Bearer tokens only. The session cookie is ignored, so a browser cannot carry a signed-in user's
     * cookie into a tool call, and CSRF does not apply.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 5)
    SecurityFilterChain mcpSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            Converter<Jwt, AbstractAuthenticationToken> authenticationConverter)
            throws Exception {
        http.securityMatcher("/workspaces/*/mcp")
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(new DefaultBearerTokenResolver())
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(authenticationConverter)))
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated());
        SecurityHeaders.apply(http);
        return http.build();
    }
}
