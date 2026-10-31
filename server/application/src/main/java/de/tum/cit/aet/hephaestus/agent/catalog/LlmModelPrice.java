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
import jakarta.persistence.Table;
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
 * Effective-dated price row for an instance {@link LlmModel}. GLOBAL. Repricing closes the
 * open row's {@code effective_to} and inserts a new one in one transaction; per-1M-token units.
 */
@Entity
@Table(name = "llm_model_price")
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class LlmModelPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false, foreignKey = @ForeignKey(name = "fk_llm_model_price_model"))
    @ToString.Exclude
    private LlmModel model;

    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_mode", nullable = false, length = 16)
    private PricingMode pricingMode;

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
    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Nullable
    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = Instant.now();
    }
}
