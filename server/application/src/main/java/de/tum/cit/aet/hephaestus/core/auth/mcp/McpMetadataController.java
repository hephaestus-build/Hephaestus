package de.tum.cit.aet.hephaestus.core.auth.mcp;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.ApiPath;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authorization server metadata (RFC 8414) and protected resource metadata (RFC 9728) for the workspace
 * MCP endpoints. A resource's metadata is answered for any well-formed workspace path, so it reveals no
 * workspace's existence.
 */
@RestController
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
@Tag(name = "MCP authorization", description = "OAuth endpoints and consent for MCP clients")
public class McpMetadataController {

    private final McpResources resources;

    McpMetadataController(McpResources resources) {
        this.resources = resources;
    }

    @GetMapping(value = "/.well-known/oauth-authorization-server", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll()")
    @Operation(summary = "Authorization server metadata (RFC 8414)", operationId = "getMcpAuthorizationServerMetadata")
    public Map<String, Object> authorizationServer() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuer", resources.issuer());
        metadata.put("authorization_endpoint", resources.authorizationEndpoint());
        metadata.put("token_endpoint", resources.tokenEndpoint());
        metadata.put("response_types_supported", List.of("code"));
        metadata.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        metadata.put("code_challenge_methods_supported", List.of("S256"));
        metadata.put("token_endpoint_auth_methods_supported", List.of("none"));
        metadata.put("scopes_supported", List.of(McpScope.READ.value(), McpScope.WRITE.value()));
        metadata.put("client_id_metadata_document_supported", true);
        metadata.put("authorization_response_iss_parameter_supported", true);
        return metadata;
    }

    @GetMapping(
            value = "/.well-known/oauth-protected-resource/{*resourcePath}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll()")
    @Operation(
            summary = "Protected resource metadata of a workspace MCP endpoint (RFC 9728)",
            operationId = "getMcpResourceMetadata")
    public ResponseEntity<Map<String, Object>> protectedResource(@PathVariable String resourcePath) {
        // RFC 9728 § 3.1 inserts the resource's path after the well-known prefix.
        String prefix = ApiPath.PREFIX + "/workspaces/";
        if (!resourcePath.startsWith(prefix) || !resourcePath.endsWith("/mcp")) {
            return ResponseEntity.notFound().build();
        }
        String slug = resourcePath.substring(prefix.length(), resourcePath.length() - "/mcp".length());
        String resource = resources.resourceFor(slug);
        if (resources.slugOf(resource).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("resource", resource);
        metadata.put("authorization_servers", List.of(resources.issuer()));
        metadata.put("scopes_supported", List.of(McpScope.READ.value(), McpScope.WRITE.value()));
        metadata.put("bearer_methods_supported", List.of("header"));
        return ResponseEntity.ok(metadata);
    }
}
