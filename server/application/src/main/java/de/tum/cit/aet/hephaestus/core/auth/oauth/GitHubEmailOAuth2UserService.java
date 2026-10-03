package de.tum.cit.aet.hephaestus.core.auth.oauth;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.client.RestClient;

/**
 * GitHub user service that augments the {@code /user} attributes with the account's
 * <b>primary, verified</b> email from {@code GET /user/emails} (requires the already-requested
 * {@code user:email} scope). GitHub's {@code /user.email} is null/private/unverified for most users,
 * so it is not a trustworthy verification signal; the {@code /user/emails} entry is.
 *
 * <p>Mirrors Keycloak's {@code GitHubIdentityProvider.searchEmail()}. Injects {@code email} and
 * {@code email_verified=true} attributes consumed by {@link VerifiedEmailResolver}. Only applied to
 * GitHub registrations (github, gh-ws-*) — see {@code AuthSecurityConfig.oauthUserService()}.
 *
 * @see <a href="https://docs.github.com/en/rest/users/emails">GitHub REST: /user/emails</a>
 */
public class GitHubEmailOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private static final Logger log = LoggerFactory.getLogger(GitHubEmailOAuth2UserService.class);
    private static final ParameterizedTypeReference<List<Map<String, Object>>> EMAIL_LIST =
            new ParameterizedTypeReference<>() {};
    private static final String EMAILS_URI = "https://api.github.com/user/emails";

    private final DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
    private final RestClient restClient = RestClient.create();

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User user = delegate.loadUser(userRequest);
        String nameAttrKey = Objects.requireNonNull(
                userRequest
                        .getClientRegistration()
                        .getProviderDetails()
                        .getUserInfoEndpoint()
                        .getUserNameAttributeName(),
                "GitHub user-name attribute must be configured");

        Map<String, Object> attrs = new HashMap<>(user.getAttributes());
        fetchPrimaryVerifiedEmail(userRequest).ifPresent(email -> {
            attrs.put("email", email);
            attrs.put("email_verified", Boolean.TRUE);
        });
        return new DefaultOAuth2User(user.getAuthorities(), attrs, nameAttrKey);
    }

    private Optional<String> fetchPrimaryVerifiedEmail(OAuth2UserRequest userRequest) {
        try {
            List<Map<String, Object>> emails = restClient
                    .get()
                    .uri(EMAILS_URI)
                    .header(
                            HttpHeaders.AUTHORIZATION,
                            "Bearer " + userRequest.getAccessToken().getTokenValue())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(EMAIL_LIST);
            return selectPrimaryVerifiedEmail(emails);
        } catch (RuntimeException ex) {
            // Email enrichment is best-effort: login must not fail because /user/emails is unreachable.
            // Without it the email is simply stored unverified (VerifiedEmailResolver returns verified=false).
            log.warn("auth.github: /user/emails lookup failed; email stored unverified: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /** The {@code primary && verified} address, or empty. Pure — the verification policy under test. */
    static Optional<String> selectPrimaryVerifiedEmail(@Nullable List<Map<String, Object>> emails) {
        if (emails == null) {
            return Optional.empty();
        }
        return emails.stream()
                .filter(e -> Boolean.TRUE.equals(e.get("primary")) && Boolean.TRUE.equals(e.get("verified")))
                .map(e -> (String) e.get("email"))
                .filter(s -> s != null && !s.isBlank())
                .findFirst();
    }
}
