package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.workspace.spi.AiModelBrand;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Instant;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import org.jspecify.annotations.Nullable;

/**
 * The settings that an instance model ({@link LlmModel}) and a workspace model ({@link WorkspaceLlmModel}) share. ADR
 * 0083 keeps them in two tables for tenancy; their columns, their settings rules and their readiness live here once.
 */
@MappedSuperclass
@Getter
@Setter
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public abstract class LlmCatalogModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

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

    /** The context window the provider's model metadata reported; {@link #getContextWindow()} wins over it. */
    @Nullable
    @Column(name = "discovered_context_window")
    private Integer discoveredContextWindow;

    /** The maximum output the provider's model metadata reported; {@link #getMaxOutputTokens()} wins over it. */
    @Nullable
    @Column(name = "discovered_max_output_tokens")
    private Integer discoveredMaxOutputTokens;

    /** Null until a probe establishes the model's forced tool-choice support. */
    @Nullable
    @Column(name = "forced_tool_choice")
    private Boolean forcedToolChoice;

    /** The reasoning effort requested of the model; null sends none, and the provider's default applies. */
    @Nullable
    @Enumerated(EnumType.STRING)
    @Column(name = "reasoning_effort", length = ReasoningEffort.MAX_LENGTH)
    private ReasoningEffort reasoningEffort;

    @Nullable
    @Enumerated(EnumType.STRING)
    @Column(name = "brand", length = 16)
    private AiModelBrand brand;

    @Embedded
    private DataHandlingFacts dataHandling = new DataHandlingFacts();

    /**
     * The {@link LlmCallPool} whose capacity the model's calls share. The database gives a model its own when none is
     * set, also when the model leaves a shared one. Only the instance admin moves it. Backed by
     * {@code sfk_llm_model_call_pool} and {@code sfk_ws_llm_model_call_pool}.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "call_pool_id", nullable = false, insertable = false, updatable = false)
    private Long callPoolId;

    @ColumnDefault("false")
    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Nullable
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Null-safe accessor: Hibernate sets the embeddable to {@code null} when every column is null. */
    public DataHandlingFacts getDataHandling() {
        if (dataHandling == null) {
            dataHandling = new DataHandlingFacts();
        }
        return dataHandling;
    }

    public DataHandlingTier getDataHandlingTier() {
        return getDataHandling().tier();
    }

    /** The limits the model runs with: an admin's, else the provider's, else derived ({@link ModelLimits}). */
    public ModelLimits limits() {
        return ModelLimits.of(
                upstreamModelId, contextWindow, maxOutputTokens, discoveredContextWindow, discoveredMaxOutputTokens);
    }

    /** The settings of a new model. Enabling it is the caller's step, after its price is known. */
    public void applySettings(LlmModelSettings settings) {
        displayName = settings.displayName();
        upstreamModelId = settings.upstreamModelId();
        contextWindow = settings.contextWindow();
        maxOutputTokens = settings.maxOutputTokens();
        reasoningEffort = settings.reasoningEffort();
        brand = settings.brand();
        dataHandling = DataHandlingFacts.of(settings.operatedBy(), settings.dataHandlingNote());
    }

    /** A partial change: an absent field keeps its value, and a clear flag removes it. */
    public void changeSettings(LlmModelSettingsChange change) {
        if (change.displayName() != null) displayName = change.displayName();
        if (Boolean.TRUE.equals(change.clearContextWindow())) contextWindow = null;
        else if (change.contextWindow() != null) contextWindow = change.contextWindow();
        if (Boolean.TRUE.equals(change.clearMaxOutputTokens())) maxOutputTokens = null;
        else if (change.maxOutputTokens() != null) maxOutputTokens = change.maxOutputTokens();
        if (Boolean.TRUE.equals(change.clearReasoningEffort())) reasoningEffort = null;
        else if (change.reasoningEffort() != null) reasoningEffort = change.reasoningEffort();
        if (Boolean.TRUE.equals(change.clearBrand())) brand = null;
        else if (change.brand() != null) brand = change.brand();
        dataHandling =
                getDataHandling().updated(change.clearDataHandling(), change.operatedBy(), change.dataHandlingNote());
        if (change.enabled() != null) enabled = change.enabled();
    }

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
