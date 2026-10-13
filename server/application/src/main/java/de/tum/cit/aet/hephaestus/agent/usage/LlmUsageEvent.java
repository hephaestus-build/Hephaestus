package de.tum.cit.aet.hephaestus.agent.usage;

import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One row per LLM-consuming unit of work — the append-only spend ledger the rollup and the monthly
 * budget cap are computed from. Never update a row.
 *
 * <p>{@code sourceId} is a soft reference (no FK) to the originating {@code agent_job.id} or
 * {@code mentor_turn.id}: accounting must survive source-row deletion. Source kind, id, and attempt
 * form the idempotency key, so retries are billed once each without collapsing distinct provider calls.
 */
@Entity
@Table(
        name = "llm_usage_event",
        indexes = {
            @Index(name = "idx_llm_usage_ws_time", columnList = "workspace_id, occurred_at"),
            // The retention sweep filters on age alone, which the composite above cannot serve: its
            // leading column is absent from that predicate.
            @Index(name = "idx_llm_usage_occurred_at", columnList = "occurred_at")
        },
        uniqueConstraints =
                @UniqueConstraint(
                        name = "ux_llm_usage_event_source_attempt",
                        columnNames = {"source_type", "source_id", "source_attempt"}))
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class LlmUsageEvent {

    @Id
    @EqualsAndHashCode.Include
    @Column(nullable = false, updatable = false)
    private UUID id;

    @NonNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    @ToString.Exclude
    private Workspace workspace;

    @NonNull
    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 40)
    private LlmUsageJobType jobType;

    @NonNull
    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;

    @NonNull
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20, updatable = false)
    private LlmUsageSourceType sourceType;

    @ColumnDefault("0")
    @Column(name = "source_attempt", nullable = false, updatable = false)
    private int sourceAttempt;

    @Nullable
    @Column(name = "model", length = 128)
    private String model;

    @ColumnDefault("0")
    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @ColumnDefault("0")
    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @ColumnDefault("0")
    @Column(name = "cache_read_tokens", nullable = false)
    private long cacheReadTokens;

    @ColumnDefault("0")
    @Column(name = "cache_write_tokens", nullable = false)
    private long cacheWriteTokens;

    @ColumnDefault("1")
    @Column(name = "total_calls", nullable = false)
    private int totalCalls = 1;

    /**
     * USD cost of this unit of work. Null when the model's price is unknown — the sums exclude such a
     * row rather than counting it as $0. Clamped into range by {@link LlmPriceSnapshot#calculateCost}.
     */
    @Nullable
    @Column(name = "cost_usd", precision = 18, scale = 6)
    private BigDecimal costUsd;

    /** When the work finished — the rollup and month-window time axis. */
    @NonNull
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @NonNull
    @ColumnDefault("'UNPRICED'")
    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_state", nullable = false, length = 16)
    private PricingState pricingState = PricingState.UNPRICED;

    @ColumnDefault("0")
    @Column(name = "reasoning_tokens", nullable = false)
    private long reasoningTokens;

    @NonNull
    @ColumnDefault("'INSTANCE'")
    @Enumerated(EnumType.STRING)
    @Column(name = "funding_source", nullable = false, length = 16)
    private FundingSource fundingSource = FundingSource.INSTANCE;

    /** Provenance: the {@code llm_model_price} row applied to this event. Soft ref (no FK). */
    @Nullable
    @Column(name = "applied_price_id")
    private Long appliedPriceId;

    /** Provenance: the {@code workspace_llm_model} row applied to this event. Soft ref (no FK). */
    @Nullable
    @Column(name = "applied_workspace_model_id")
    private Long appliedWorkspaceModelId;

    /**
     * The rates actually applied, frozen. The two provenance ids above are live refs — repricing or
     * deleting the row they point at must not change what a historical event was charged.
     */
    @Nullable
    @Column(name = "applied_per_1m_input_usd", precision = 18, scale = 8)
    private BigDecimal appliedPer1mInputUsd;

    @Nullable
    @Column(name = "applied_per_1m_output_usd", precision = 18, scale = 8)
    private BigDecimal appliedPer1mOutputUsd;

    @Nullable
    @Column(name = "applied_per_1m_cache_read_usd", precision = 18, scale = 8)
    private BigDecimal appliedPer1mCacheReadUsd;

    @Nullable
    @Column(name = "applied_per_1m_cache_write_usd", precision = 18, scale = 8)
    private BigDecimal appliedPer1mCacheWriteUsd;

    /**
     * Which of the two independent spend records these token counts came from — see
     * {@link UsageProvenance}. Nullable only because rows written before this column existed have no
     * honest answer; every row this application writes carries one.
     */
    @Nullable
    @Enumerated(EnumType.STRING)
    @Column(name = "usage_provenance", length = 16)
    private UsageProvenance usageProvenance;
}
