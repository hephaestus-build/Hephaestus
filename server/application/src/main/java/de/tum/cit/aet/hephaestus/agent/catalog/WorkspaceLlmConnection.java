package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.security.EncryptedStringConverter;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.LlmConnectionPlatform;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;
import org.jspecify.annotations.Nullable;

/**
 * Workspace-owned BYO LLM connection: same shape as {@link LlmConnection}, but tenant-scoped
 * and self-funded — the workspace admin owns the key and the bill.
 */
@Entity
@Table(
        name = "workspace_llm_connection",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "ux_ws_llm_connection_ws_slug",
                    columnNames = {"workspace_id", "slug"}),
            @UniqueConstraint(
                    name = "ux_ws_llm_connection_id_ws",
                    columnNames = {"id", "workspace_id"}),
        })
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class WorkspaceLlmConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "workspace_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_ws_llm_connection_workspace"))
    @ToString.Exclude
    private Workspace workspace;

    @Column(name = "slug", nullable = false, length = 64)
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
    private String apiProtocol;

    @ColumnDefault("'BEARER'")
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_mode", nullable = false, length = 16, updatable = false)
    private LlmAuthMode authMode = LlmAuthMode.BEARER;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "api_key", columnDefinition = "TEXT")
    @ToString.Exclude
    private @Nullable String apiKey;

    @ColumnDefault("false")
    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Nullable
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
}
