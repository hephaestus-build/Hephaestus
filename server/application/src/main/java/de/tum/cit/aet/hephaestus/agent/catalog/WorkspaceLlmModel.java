package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.workspace.Workspace;
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
import java.math.BigDecimal;
import java.time.Instant;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;
import org.jspecify.annotations.Nullable;

/**
 * Workspace-owned BYO model with its current price inline (no history). {@code workspace_id} is
 * denormalized so every query can carry a workspace predicate for the tenancy inspector.
 */
@Entity
@Table(
        name = "workspace_llm_model",
        uniqueConstraints = {
            // Named here so the ddl-auto test schema matches the changelog's: the create path catches this
            // constraint by name.
            @UniqueConstraint(
                    name = "ux_ws_llm_model_connection_upstream",
                    columnNames = {"connection_id", "upstream_model_id"}),
            @UniqueConstraint(
                    name = "ux_ws_llm_model_ws_slug",
                    columnNames = {"workspace_id", "slug"}),
            @UniqueConstraint(
                    name = "ux_ws_llm_model_id_ws",
                    columnNames = {"id", "workspace_id"}),
        })
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class WorkspaceLlmModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false, foreignKey = @ForeignKey(name = "fk_ws_llm_model_workspace"))
    @ToString.Exclude
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connection_id", nullable = false, foreignKey = @ForeignKey(name = "fk_ws_llm_model_connection"))
    @ToString.Exclude
    private WorkspaceLlmConnection connection;

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

    @ColumnDefault("'UNPRICED'")
    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_mode", nullable = false, length = 16)
    private PricingMode pricingMode = PricingMode.UNPRICED;

    @Nullable
    @Column(name = "per_1m_input_usd", precision = 18, scale = 8)
    private BigDecimal per1mInputUsd;

    @Nullable
    @Column(name = "per_1m_output_usd", precision = 18, scale = 8)
    private BigDecimal per1mOutputUsd;

    @Nullable
    @Column(name = "per_1m_cache_read_usd", precision = 18, scale = 8)
    private BigDecimal per1mCacheReadUsd;

    @Nullable
    @Column(name = "per_1m_cache_write_usd", precision = 18, scale = 8)
    private BigDecimal per1mCacheWriteUsd;

    @ColumnDefault("'USD'")
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    @Nullable
    @Column(name = "price_note", length = 500)
    private String priceNote;

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
