package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.security.SecretBinding;
import de.tum.cit.aet.hephaestus.core.security.SystemSecrets;
import de.tum.cit.aet.hephaestus.workspace.spi.LlmConnectionPlatform;
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
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;
import org.jspecify.annotations.Nullable;

/**
 * Instance-owned LLM provider connection: an endpoint the instance can talk to, its credential,
 * and how its traffic egresses. {@code app_admin}-owned and GLOBAL (not tenant-scoped).
 */
@Entity
@Table(
        name = "llm_connection",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "ux_llm_connection_slug",
                        columnNames = {"slug"}))
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class LlmConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "slug", nullable = false, length = 64, updatable = false)
    private String slug;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Nullable
    @Enumerated(EnumType.STRING)
    @Column(name = "connection_platform", length = 32)
    private LlmConnectionPlatform connectionPlatform;

    @Column(name = "base_url", nullable = false, length = 2048)
    @ToString.Exclude
    private String baseUrl;

    @Column(name = "api_protocol", nullable = false, length = 40)
    private LlmApiProtocol apiProtocol;

    @ColumnDefault("'BEARER'")
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_mode", nullable = false, length = 16, updatable = false)
    private LlmAuthMode authMode = LlmAuthMode.BEARER;

    /** Sealed by {@link SystemSecrets}, bound to the slug, which never changes. */
    @Column(name = "api_key", columnDefinition = "TEXT")
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @ToString.Exclude
    private @Nullable String sealedApiKey;

    @ColumnDefault("false")
    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }

    /** Whether a value is stored, without opening it. */
    public boolean hasApiKey() {
        return sealedApiKey != null;
    }

    public @Nullable String apiKey(SystemSecrets secrets) {
        return sealedApiKey == null ? null : secrets.openText(sealedApiKey, secretBinding());
    }

    /** Seals {@code value} under the active system key, bound to this row. */
    public void replaceApiKey(@Nullable String value, SystemSecrets secrets) {
        this.sealedApiKey = value == null ? null : secrets.sealText(value, secretBinding());
    }

    /** Seals the stored value again under the active system key; false when it already uses that key. */
    public boolean resealWithActiveKey(SystemSecrets secrets) {
        String sealed = sealedApiKey;
        if (sealed == null || secrets.isSealedWithActiveKey(sealed)) return false;
        this.sealedApiKey = secrets.sealText(secrets.openText(sealed, secretBinding()), secretBinding());
        return true;
    }

    private SecretBinding secretBinding() {
        return secretBinding(slug);
    }

    static SecretBinding secretBinding(String slug) {
        return SecretBinding.of("llm_connection.api_key", slug);
    }
}
