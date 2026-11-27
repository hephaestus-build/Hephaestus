package de.tum.cit.aet.hephaestus.core.auth.provider;

import de.tum.cit.aet.hephaestus.core.security.SecretBinding;
import de.tum.cit.aet.hephaestus.core.security.SystemSecrets;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** Provider rows are instance-scoped; environment values seed missing rows without overwriting administrator edits. */
@Entity
@Table(
        name = "login_provider",
        // One login OAuth app per instance: a (type, baseUrl) pair identifies a single SCM
        // instance, so two
        // rows for e.g. https://gitlab.lrz.de would mean two login buttons → split identities for
        // one user.
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_login_provider_type_base_url",
                        columnNames = {"type", "base_url"}))
@Getter
@Setter
@NoArgsConstructor
public class LoginProvider {

    public enum ProviderType {
        GITHUB,
        GITLAB,
        /** Link-only OIDC provider; attaching its identity requires an authenticated account. */
        SLACK,
        /** Link-only OAuth2 provider; Outline identity comes from POST /api/auth.info, without OIDC discovery. */
        OUTLINE,
        /** A generic OpenID Connect issuer; {@code base_url} holds the issuer. It signs in. */
        OIDC;

        /** Link-only providers cannot start an unauthenticated sign-in. */
        public boolean isLinkOnly() {
            return this == SLACK || this == OUTLINE;
        }

        /** Whether a person can sign in through the type, so that a provider of it keeps an instance open. */
        public boolean signsIn() {
            return this == GITHUB || this == GITLAB || this == OIDC;
        }

        public static Set<ProviderType> signingIn() {
            return Arrays.stream(values()).filter(ProviderType::signsIn).collect(Collectors.toUnmodifiableSet());
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "registration_id", nullable = false, unique = true, length = 64, updatable = false)
    private String registrationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10)
    private ProviderType type;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "base_url", nullable = false, length = 512)
    private String baseUrl;

    @Column(name = "client_id", nullable = false, length = 512)
    private String clientId;

    /** Sealed by {@link SystemSecrets}, bound to the registration id, which never changes. */
    @Column(name = "client_secret", nullable = false, columnDefinition = "TEXT")
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private String sealedClientSecret;

    /** Space-separated OAuth scopes. */
    @Column(name = "scopes", nullable = false, length = 512)
    private String scopes;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "seeded_from_env", nullable = false)
    private boolean seededFromEnv = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Normalize on write (trim + strip trailing slashes) so the {@code (type, base_url)} uniqueness
     * is meaningful — {@code https://gitlab.lrz.de} and {@code https://gitlab.lrz.de/} are the same
     * instance. An OIDC issuer compares as an exact string (OpenID Connect Core § 3.1.3.7), so it
     * keeps its spelling; set the type first.
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl =
                type == ProviderType.OIDC ? baseUrl.trim() : baseUrl.trim().replaceAll("/+$", "");
    }

    /**
     * The version of the row that a sign-in registration is built from. A sign-in completes only against the row
     * version that started it, so an admin who replaces or edits the row meanwhile never gets a subject keyed into
     * the new row's namespace.
     */
    public String registrationVersion() {
        return String.join(
                "|",
                String.valueOf(id),
                String.valueOf(type),
                baseUrl,
                clientId,
                scopes,
                displayName,
                String.valueOf(updatedAt));
    }

    public String clientSecret(SystemSecrets secrets) {
        return secrets.openText(sealedClientSecret, secretBinding());
    }

    /** Seals {@code value} under the active system key, bound to this row. */
    public void replaceClientSecret(String value, SystemSecrets secrets) {
        this.sealedClientSecret = secrets.sealText(value, secretBinding());
    }

    /** Seals the stored value again under the active system key; false when it already uses that key. */
    public boolean resealWithActiveKey(SystemSecrets secrets) {
        String sealed = sealedClientSecret;
        if (secrets.isSealedWithActiveKey(sealed)) return false;
        this.sealedClientSecret = secrets.sealText(secrets.openText(sealed, secretBinding()), secretBinding());
        return true;
    }

    private SecretBinding secretBinding() {
        return SecretBinding.of("login_provider.client_secret", registrationId);
    }
}
