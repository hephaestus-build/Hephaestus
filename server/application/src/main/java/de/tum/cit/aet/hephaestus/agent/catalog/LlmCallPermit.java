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
import org.jspecify.annotations.Nullable;

/** One fenced call request, from queue admission through transport closure. Closed rows retain rate history. */
@Entity
@Table(name = "llm_call_permit", indexes = @Index(name = "idx_llm_call_permit_pool", columnList = "pool_id"))
@Getter
@NoArgsConstructor
public class LlmCallPermit {

    @Id
    @Column(name = "id")
    private UUID id;

    /** Backed by {@code sfk_llm_call_permit_pool}, which prevents deletion while a request exists. */
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

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @ColumnDefault("'WAITING'")
    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Nullable
    @Column(name = "granted_at")
    private Instant grantedAt;

    @Nullable
    @Column(name = "closed_at")
    private Instant closedAt;

    @ColumnDefault("0")
    @Column(name = "requests", nullable = false)
    private int requests;

    @ColumnDefault("0")
    @Column(name = "tokens", nullable = false)
    private long tokens;
}
