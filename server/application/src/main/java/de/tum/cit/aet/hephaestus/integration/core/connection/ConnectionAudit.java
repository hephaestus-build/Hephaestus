package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * Append-only audit row for Connection lifecycle events.
 *
 * <p>{@code correlation_id} unique within (connection, event_type) gives webhook
 * idempotency: a redelivered SUSPEND with the same {@code X-GitHub-Delivery} does
 * NOT cause a state flap — the conflicting INSERT is caught by the unique index
 * and the caller short-circuits.
 *
 * <p><b>Uniqueness contract:</b> {@code uq_connection_audit_idempotency} over
 * (connection_id, event_type, correlation_id) is declared in Liquibase as a
 * partial unique index with {@code NULLS NOT DISTINCT} (Postgres 15+). The
 * NULLS-NOT-DISTINCT semantics close a real bug: webhook replays without a
 * correlation id (manual admin events, some install lifecycle events) would
 * otherwise duplicate under the default NULLS-DISTINCT contract. A JPA
 * {@code @UniqueConstraint} cannot express NULLS NOT DISTINCT, so the
 * contract lives in the migration ({@code 1780313973588_changelog.xml},
 * changeset {@code 1780313973588-3}) and is intentionally NOT mirrored as a
 * {@code @Table.uniqueConstraints} annotation here — see ADR 0016.
 */
@Entity
@Table(name = "connection_audit")
public class ConnectionAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @Nullable Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "connection_id", nullable = false)
    private Connection connection;

    @Column(name = "event_type", nullable = false, length = 48)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", length = 32)
    @Nullable
    private IntegrationState fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", length = 32)
    @Nullable
    private IntegrationState toState;

    @Column(name = "actor_kind", nullable = false, length = 32)
    private String actorKind;

    @Column(name = "actor_ref", length = 256)
    @Nullable
    private String actorRef;

    @Column(name = "actor_account_id")
    @Nullable
    private Long actorAccountId;

    @Column(name = "correlation_id", length = 64)
    @Nullable
    private String correlationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", columnDefinition = "jsonb")
    @Nullable
    private String detail;

    @CreationTimestamp
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private @Nullable Instant occurredAt;

    protected ConnectionAudit() {}

    public ConnectionAudit(
            Connection connection,
            String eventType,
            @Nullable IntegrationState fromState,
            @Nullable IntegrationState toState,
            String actorKind,
            @Nullable String actorRef,
            @Nullable String correlationId,
            @Nullable String detail) {
        this.connection = connection;
        this.eventType = eventType;
        this.fromState = fromState;
        this.toState = toState;
        this.actorKind = actorKind;
        this.actorRef = actorRef;
        this.correlationId = correlationId;
        this.detail = detail;
    }

    public void setActorAccountId(@Nullable Long accountId) {
        this.actorAccountId = accountId;
    }

    public @Nullable Long getActorAccountId() {
        return actorAccountId;
    }

    public @Nullable Long getId() {
        return id;
    }

    public Connection getConnection() {
        return connection;
    }

    public String getEventType() {
        return eventType;
    }

    @Nullable
    public IntegrationState getFromState() {
        return fromState;
    }

    @Nullable
    public IntegrationState getToState() {
        return toState;
    }

    public String getActorKind() {
        return actorKind;
    }

    @Nullable
    public String getActorRef() {
        return actorRef;
    }

    @Nullable
    public String getCorrelationId() {
        return correlationId;
    }

    @Nullable
    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return Objects.requireNonNull(occurredAt, "Connection audit has not been persisted");
    }
}
