package de.tum.cit.aet.hephaestus.core.auth.provider;

import de.tum.cit.aet.hephaestus.core.security.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * An instance-scoped OAuth2 login provider (a sign-in option offered on the login page) — GitHub,
 * GitLab.com, or a self-hosted GitLab. A deployment ships with seeded defaults (env → seed) and an
 * instance admin can add more (e.g. a self-hosted GitLab) without a redeploy.
 *
 * <p>{@code registrationId} is the OAuth callback id ({@code /login/oauth2/code/{registrationId}}) and
 * what {@code IdentityLink} provider resolution and the admin allowlist key on, so it is stable and
 * unique. The client secret is sealed at rest by {@link EncryptedStringConverter} (AES-256-GCM); it
 * never leaves the server.
 */
@Entity
@Table(
        name = "login_provider",
        // One login OAuth app per instance: a (type, baseUrl) pair identifies a single SCM instance, so two
        // rows for e.g. https://gitlab.lrz.de would mean two login buttons → split identities for one user.
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_login_provider_type_base_url",
                        columnNames = {"type", "base_url"}))
@Getter
@Setter
@NoArgsConstructor
public class LoginProvider {

    /** A login provider's git-provider family. A self-hosted GitLab is {@code GITLAB} with its own baseUrl. */
    public enum ProviderType {
        GITHUB,
        GITLAB,
        /** Organizational OpenID Connect; baseUrl is the exact issuer, including its realm path. */
        OIDC,
        /**
         * "Sign in with Slack" (OIDC). A <em>link-only</em> secondary provider: it is never offered on the
         * public login picker ({@code IdentityProviderCatalog.listRegistrations} filters it out) and is only
         * reachable by registration id from the authenticated account-linking flow. Unlike the SCM providers
         * it uses the OIDC path (id_token with a {@code sub} + verified {@code team_id} claim), not OAuth2.
         */
        SLACK,
        /**
         * Outline wiki (v0.77+). A <em>link-only</em> secondary provider like {@link #SLACK}, but plain
         * OAuth2, NOT OIDC — Outline issues no id_token and has no discovery/userinfo endpoint; identity
         * comes from {@code POST {base}/api/auth.info} with the user's access token (see
         * {@code OutlineAuthInfoUserService}). Per-instance {@code baseUrl} (self-hosted), validated like a
         * self-hosted GitLab (HTTPS + SSRF guard). The Outline team id keys {@code IdentityLink.teamId}.
         */
        OUTLINE;

        /**
         * Link-only providers are never a primary sign-in: they can only attach an identity to an already
         * authenticated account (Slack workspace identity, Outline wiki identity). Enforced at flow begin
         * ({@code AuthBeginController}) and again at provisioning ({@code AccountProvisioningService}).
         */
        public boolean isLinkOnly() {
            return this == SLACK || this == OUTLINE;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "registration_id", nullable = false, unique = true, length = 64)
    private String registrationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private ProviderType type;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    /** OAuth host root, or the exact issuer URL for an organizational OIDC provider. */
    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    @Column(name = "client_id", nullable = false, length = 512)
    private String clientId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "client_secret", nullable = false, columnDefinition = "TEXT")
    private String clientSecret;

    /** Space-separated OAuth scopes. */
    @Column(name = "scopes", nullable = false, length = 512)
    private String scopes;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** True when the row was seeded from {@code hephaestus.auth.*} env config rather than created via the admin UI. */
    @Column(name = "seeded_from_env", nullable = false)
    private boolean seededFromEnv = false;

    /** Explicit instance-operator approval for the optional read-only Keycloak directory adapter. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "directory_group_ids", columnDefinition = "jsonb", nullable = false)
    private Set<String> directoryGroupIds = Set.of();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Normalize on write (trim + strip trailing slashes) so the {@code (type, base_url)} uniqueness is
     * meaningful — {@code https://gitlab.lrz.de} and {@code https://gitlab.lrz.de/} are the same instance.
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl =
                type == ProviderType.OIDC ? baseUrl.trim() : baseUrl.trim().replaceAll("/+$", "");
    }
}
