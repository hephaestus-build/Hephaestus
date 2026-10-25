package de.tum.cit.aet.hephaestus.core.auth.oauth;

import de.tum.cit.aet.hephaestus.core.WebClientConnectors;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Outline supplies identity through POST /api/auth.info, without OIDC discovery or a userinfo endpoint.
 * Both immutable user and team ids are required; missing ids cause {@link OAuth2AuthenticationException}.
 */
public class OutlineAuthInfoUserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    static final String TEAM_ID_ATTRIBUTE = "team_id";
    private static final String SUBJECT_ATTRIBUTE = "id";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;

    public OutlineAuthInfoUserService(WebClient.Builder webClients) {
        this(webClients.clientConnector(WebClientConnectors.ssrfGuarded()).build());
    }

    /** Test seam: inject a WebClient with a stubbed exchange function. */
    OutlineAuthInfoUserService(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        String authInfoUri = Objects.requireNonNull(
                userRequest
                        .getClientRegistration()
                        .getProviderDetails()
                        .getUserInfoEndpoint()
                        .getUri(),
                "Outline auth.info URI must be configured");
        Map<String, Object> body;
        try {
            body = webClient
                    .post()
                    .uri(authInfoUri)
                    .header(
                            HttpHeaders.AUTHORIZATION,
                            "Bearer " + userRequest.getAccessToken().getTokenValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{}")
                    .retrieve()
                    .bodyToMono(JSON_OBJECT)
                    .block(REQUEST_TIMEOUT);
        } catch (RuntimeException ex) {
            // Identity IS the product of this call — unlike GitHub's best-effort email enrichment,
            // an unreachable auth.info must fail the login, not proceed with an empty principal.
            throw authError("outline_auth_info_unavailable", "Outline auth.info call failed: " + ex.getMessage(), ex);
        }
        Map<String, Object> attributes = toAttributes(body);
        return new DefaultOAuth2User(Set.of(new SimpleGrantedAuthority("OAUTH2_USER")), attributes, SUBJECT_ATTRIBUTE);
    }

    /**
     * Flattens Outline's nested {@code {data:{user,team}}} payload into principal attributes. Fails
     * closed when the user id or team id is missing.
     */
    static Map<String, Object> toAttributes(@Nullable Map<String, Object> body) {
        Map<String, Object> data = nestedObject(body, "data");
        Map<String, Object> user = nestedObject(data, "user");
        Map<String, Object> team = nestedObject(data, "team");

        String userId = stringValue(user, "id");
        if (userId == null) {
            throw authError("outline_missing_user_id", "Outline auth.info returned no user id");
        }
        String teamId = stringValue(team, "id");
        if (teamId == null) {
            throw authError("outline_missing_team_id", "Outline auth.info returned no team id");
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(SUBJECT_ATTRIBUTE, userId);
        putIfPresent(attributes, "name", stringValue(user, "name"));
        putIfPresent(attributes, "email", stringValue(user, "email"));
        putIfPresent(attributes, "avatar_url", stringValue(user, "avatarUrl"));
        attributes.put(TEAM_ID_ATTRIBUTE, teamId);
        putIfPresent(attributes, "team_name", stringValue(team, "name"));
        return attributes;
    }

    private static OAuth2AuthenticationException authError(String code, String description) {
        return authError(code, description, null);
    }

    private static OAuth2AuthenticationException authError(String code, String description, @Nullable Throwable cause) {
        return new OAuth2AuthenticationException(new OAuth2Error(code, description, null), description, cause);
    }

    private static Map<String, Object> nestedObject(@Nullable Map<String, Object> parent, String key) {
        if (parent != null && parent.get(key) instanceof Map<?, ?> nested) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) nested;
            return typed;
        }
        return Map.of();
    }

    private static @Nullable String stringValue(Map<String, Object> map, String key) {
        return (map.get(key) instanceof String s && !s.isBlank()) ? s : null;
    }

    private static void putIfPresent(Map<String, Object> attributes, String key, @Nullable String value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }
}
