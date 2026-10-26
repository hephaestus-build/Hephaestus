package de.tum.cit.aet.hephaestus.agent.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

/**
 * One model call in flight in a {@link LlmCallPool}. The worker that makes the call holds the row and renews it; a row
 * that its worker no longer renews is reclaimed (ADR 0063: PostgreSQL is the ledger, no broker). Written only through
 * the native statements of {@link LlmCallPermitRepository}.
 */
@Entity
@Table(name = "llm_call_permit", indexes = @Index(name = "idx_llm_call_permit_pool", columnList = "pool_id"))
@Getter
@NoArgsConstructor
public class LlmCallPermit {

    @Id
    @Column(name = "id")
    private UUID id;

    /** Backed by {@code sfk_llm_call_permit_pool}, which deletes the permits of a deleted pool. */
    @Column(name = "pool_id", nullable = false)
    private Long poolId;

    @Column(name = "worker_id", nullable = false, length = 255)
    private String workerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "call_class", nullable = false, length = 16)
    private LlmCallClass callClass;

    @ColumnDefault("now()")
    @Column(name = "acquired_at", nullable = false)
    private Instant acquiredAt;

    /** Database time of the last renewal by the worker. */
    @ColumnDefault("now()")
    @Column(name = "renewed_at", nullable = false)
    private Instant renewedAt;
}
