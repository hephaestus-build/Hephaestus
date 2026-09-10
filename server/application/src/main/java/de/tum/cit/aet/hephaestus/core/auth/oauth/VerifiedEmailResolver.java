package de.tum.cit.aet.hephaestus.core.auth.oauth;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * Derives the {@code (email, verified)} pair for a federated login, following Keycloak's "trust email"
 * brokering model: an email is marked VERIFIED only when the IdP explicitly attests it. Never used to
 * resolve an account (nOAuth defence) — only to populate {@code Account.primaryEmail} /
 * {@code primaryEmailVerifiedAt} for contact.
 *
 * <ul>
 *   <li><b>GitHub</b>: the {@code /user} email is unreliable, so the trusted signal is the
 *       {@code primary && verified} entry surfaced by {@link GitHubEmailOAuth2UserService} into the
 *       {@code email} + {@code email_verified} attributes.</li>
 *   <li><b>GitLab</b>: the authenticated {@code /api/v4/user} response's {@code confirmed_at}
 *       is adapted by {@link GitLabEmailAttributes}; absent/malformed confirmation and temporary
 *       OAuth placeholder addresses remain unverified.</li>
 * </ul>
 *
 * @see <a href="https://openid.net/specs/openid-connect-core-1_0.html">OIDC Core §5.1</a>
 * @see <a href="https://docs.github.com/en/rest/users/emails">GitHub /user/emails</a>
 */
@ConditionalOnServerRole
@Component
public class VerifiedEmailResolver {

    /** {@code email} may be null; {@code verified} is true only on explicit IdP attestation of a non-blank email. */
    public record ResolvedEmail(@Nullable String email, boolean verified) {}

    public ResolvedEmail resolve(String registrationId, OAuth2User principal) {
        String email = stringAttr(principal, "email");
        boolean verified = emailVerified(principal) && email != null && !email.isBlank();
        return new ResolvedEmail(email, verified);
    }

    /**
     * True only when the IdP attests verification: OIDC ID-token {@code email_verified == true}, or the
     * provider adapter's injected {@code email_verified == true} attribute. Absent/false ⇒ unverified.
     */
    private static boolean emailVerified(OAuth2User principal) {
        if (principal instanceof OidcUser oidc) {
            Boolean claim = oidc.getEmailVerified();
            if (claim != null) {
                return claim;
            }
        }
        Object attr = principal.getAttributes().get("email_verified");
        if (attr instanceof Boolean b) {
            return b;
        }
        return attr instanceof String s && Boolean.parseBoolean(s);
    }

    @Nullable
    private static String stringAttr(OAuth2User principal, String key) {
        Map<String, Object> attrs = principal.getAttributes();
        return (attrs.get(key) instanceof String s && !s.isBlank()) ? s : null;
    }
}
