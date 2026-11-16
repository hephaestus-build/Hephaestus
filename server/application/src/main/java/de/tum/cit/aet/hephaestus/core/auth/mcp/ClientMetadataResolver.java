package de.tum.cit.aet.hephaestus.core.auth.mcp;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resolves an MCP client by its client ID metadata document (draft-ietf-oauth-client-id-metadata-document).
 * The document is fetched for every authorization request and never cached.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnBooleanProperty("hephaestus.mcp.enabled")
public class ClientMetadataResolver {

    static final int CLIENT_NAME_LENGTH = 80;

    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "[::1]", "localhost");
    private static final Pattern UNPRINTABLE = Pattern.compile("[\\p{Cc}\\p{Cf}]");

    /** What the authorization server keeps from a valid document. */
    public record ClientMetadata(String clientId, String clientName, List<String> redirectUris) {
        public ClientMetadata {
            redirectUris = List.copyOf(redirectUris);
        }
    }

    private final ClientMetadataSource source;
    private final JsonMapper jsonMapper;

    ClientMetadataResolver(ClientMetadataSource source, JsonMapper jsonMapper) {
        this.source = source;
        this.jsonMapper = jsonMapper;
    }

    /**
     * The form check that runs before sign-in: an HTTPS URL with a host and a path, and no userinfo,
     * query, fragment or dot segment (CIMD § 3).
     */
    public static Optional<URI> clientIdUrl(String clientId) {
        if (clientId.length() > McpClientGrant.URL_LENGTH) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(clientId);
        } catch (URISyntaxException malformed) {
            return Optional.empty();
        }
        String path = uri.getRawPath();
        boolean valid = "https".equals(uri.getScheme())
                && uri.getHost() != null
                && uri.getRawUserInfo() == null
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && path != null
                && path.length() > 1
                && !hasDotSegment(path);
        return valid ? Optional.of(uri) : Optional.empty();
    }

    /** Fetches and checks the document; empty when any check fails. */
    public Optional<ClientMetadata> resolve(URI clientId) {
        return source.fetch(clientId).flatMap(body -> parse(clientId, body));
    }

    private Optional<ClientMetadata> parse(URI clientId, String body) {
        JsonNode document;
        try {
            document = jsonMapper.readTree(body);
        } catch (JacksonException unreadable) {
            return Optional.empty();
        }
        if (!document.isObject()
                || !document.path("client_id").isString()
                || !clientId.toString().equals(document.path("client_id").asString())
                || !"none".equals(document.path("token_endpoint_auth_method").asString(""))) {
            return Optional.empty();
        }
        List<String> redirects = new ArrayList<>();
        for (JsonNode redirect : document.path("redirect_uris")) {
            if (!redirect.isString() || !isAllowedRedirect(redirect.asString())) {
                return Optional.empty();
            }
            redirects.add(redirect.asString());
        }
        if (redirects.isEmpty()) {
            return Optional.empty();
        }
        // Control and format characters, bidi overrides among them, could make the name read as another.
        String name = UNPRINTABLE
                .matcher(document.path("client_name").asString(""))
                .replaceAll("")
                .strip();
        String clientName = name.isEmpty() ? clientId.getHost() : name;
        if (clientName.length() > CLIENT_NAME_LENGTH) {
            clientName = clientName.substring(0, CLIENT_NAME_LENGTH);
        }
        return Optional.of(new ClientMetadata(clientId.toString(), clientName, redirects));
    }

    /**
     * Whether a requested redirect matches the document: exactly, or, for a loopback redirect that the
     * document lists, with any port when scheme, host and path are equal (RFC 8252 § 7.3).
     */
    public static boolean redirectMatches(ClientMetadata client, String requested) {
        if (client.redirectUris().contains(requested)) {
            return true;
        }
        Optional<URI> asked = parse(requested).filter(ClientMetadataResolver::isLoopback);
        if (asked.isEmpty()) {
            return false;
        }
        for (String listed : client.redirectUris()) {
            Optional<URI> registered = parse(listed).filter(ClientMetadataResolver::isLoopback);
            if (registered.isPresent() && sameButPort(registered.get(), asked.get())) {
                return true;
            }
        }
        return false;
    }

    /** Whether the client can only receive codes on this machine, which the consent page warns about. */
    public static boolean loopbackOnly(ClientMetadata client) {
        return client.redirectUris().stream()
                .map(ClientMetadataResolver::parse)
                .allMatch(uri -> uri.filter(ClientMetadataResolver::isLoopback).isPresent());
    }

    private static boolean sameButPort(URI registered, URI asked) {
        return registered.getScheme().equals(asked.getScheme())
                && registered
                        .getHost()
                        .toLowerCase(Locale.ROOT)
                        .equals(asked.getHost().toLowerCase(Locale.ROOT))
                && registered.getRawPath().equals(asked.getRawPath())
                && Objects.equals(registered.getRawQuery(), asked.getRawQuery())
                && asked.getRawFragment() == null;
    }

    private static boolean isLoopback(URI uri) {
        return "http".equals(uri.getScheme())
                && uri.getHost() != null
                && LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))
                && uri.getRawPath() != null;
    }

    /** An HTTPS redirect, or a loopback HTTP one; OAuth 2.1 § 2.3.1 admits no other scheme here. */
    private static boolean isAllowedRedirect(String redirect) {
        return redirect.length() <= McpClientGrant.URL_LENGTH
                && parse(redirect)
                        .filter(uri -> uri.getHost() != null && uri.getRawFragment() == null)
                        .filter(uri -> "https".equals(uri.getScheme()) || isLoopback(uri))
                        .isPresent();
    }

    private static Optional<URI> parse(String value) {
        try {
            return Optional.of(new URI(value));
        } catch (URISyntaxException malformed) {
            return Optional.empty();
        }
    }

    private static boolean hasDotSegment(String path) {
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }
}
