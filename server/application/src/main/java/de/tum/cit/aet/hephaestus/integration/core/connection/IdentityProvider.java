package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.jspecify.annotations.Nullable;

/**
 * Represents an identity provider instance (e.g., github.com, gitlab.lrz.de, slack.com).
 *
 * <p>Each unique combination of provider type and server URL is a distinct provider. This supports
 * multiple instances of the same provider type (e.g., GitLab SaaS and self-hosted GitLab
 * Enterprise) as well as non-SCM identity providers (Slack).
 *
 * <p>All git service entities reference an {@link IdentityProvider} via foreign key, scoping native
 * IDs to prevent cross-provider collisions; {@code identity} rows key their federated-login subject
 * by the same provider row.
 */
@Entity
@Table(
        name = "identity_provider",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_identity_provider_type_server_url",
                    columnNames = {"type", "server_url"}),
        })
@Getter
@Setter
@NoArgsConstructor
@ToString
public class IdentityProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Nullable
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private IdentityProviderType type;

    @Column(name = "server_url", nullable = false, length = 512)
    private String serverUrl;

    @Nullable
    @Column(name = "outline_origin", length = 512)
    private String outlineOrigin;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    @Nullable
    private Instant createdAt;

    public IdentityProvider(IdentityProviderType type, String serverUrl) {
        this.type = type;
        this.serverUrl = serverUrl;
    }

    @PrePersist
    @PreUpdate
    void storeOutlineOrigin() {
        outlineOrigin = outlineOrigin(type.name(), serverUrl);
    }

    static @Nullable String outlineOrigin(String providerType, String serverUrl) {
        return "OUTLINE".equals(providerType) ? ScmOrigin.of(serverUrl).orElse(null) : null;
    }

    /** The integration of a provider that hosts work. Only SCM code asks, and an OIDC issuer hosts none. */
    public IntegrationKind kind() {
        return Objects.requireNonNull(type.kind(), "An OIDC issuer is no integration");
    }

    // Proxy-aware, like BaseGitServiceEntity#equals.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IdentityProvider that)) return false;
        return id != null && id.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
