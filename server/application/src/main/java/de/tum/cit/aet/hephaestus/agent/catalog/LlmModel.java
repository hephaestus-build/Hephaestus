package de.tum.cit.aet.hephaestus.agent.catalog;

import jakarta.persistence.Column;
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
 * Instance-curated model behind a {@link LlmConnection}: an opaque upstream id plus capability
 * and visibility metadata. GLOBAL (not tenant-scoped).
 */
@Entity
@Table(
        name = "llm_model",
        uniqueConstraints = {
            // Named here so the ddl-auto test schema matches the changelog's: the create path catches this
            // constraint by name.
            @UniqueConstraint(
                    name = "ux_llm_model_connection_upstream",
                    columnNames = {"connection_id", "upstream_model_id"}),
            @UniqueConstraint(
                    name = "ux_llm_model_connection_slug",
                    columnNames = {"connection_id", "slug"}),
        })
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class LlmModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connection_id", nullable = false, foreignKey = @ForeignKey(name = "fk_llm_model_connection"))
    @ToString.Exclude
    private LlmConnection connection;

    @Column(name = "slug", nullable = false, length = 64)
    private String slug;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "upstream_model_id", nullable = false, length = 256)
    private String upstreamModelId;

    @Nullable
    @Column(name = "context_window")
    private Integer contextWindow;

    @Nullable
    @Column(name = "max_output_tokens")
    private Integer maxOutputTokens;

    @ColumnDefault("false")
    @Column(name = "supports_reasoning", nullable = false)
    private boolean supportsReasoning = false;

    @ColumnDefault("'UNCLASSIFIED'")
    @Enumerated(EnumType.STRING)
    @Column(name = "processing_location", nullable = false, length = 24)
    private LlmProcessingLocation processingLocation = LlmProcessingLocation.UNCLASSIFIED;

    @ColumnDefault("'GRANTED'")
    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 16)
    private ModelVisibility visibility = ModelVisibility.GRANTED;

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
