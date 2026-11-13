package de.tum.cit.aet.hephaestus.integration.core.consumer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One stored provider delivery for one workspace, or for the installation when {@code workspaceId} is null. Only
 * {@link ProviderEventStore} writes it, through conditional statements on the database clock; the entity maps the
 * table for the schema check. {@code scope_key} names the workspace and the subject without its event token: at most
 * one event of a scope is leased at a time, and a scope's events are claimed in the order they arrived.
 */
@Entity
@Table(
        name = "provider_event",
        indexes = {
            @Index(name = "idx_provider_event_open_scope", columnList = "scope_key, id"),
            @Index(name = "idx_provider_event_queued", columnList = "available_at, id"),
            @Index(name = "idx_provider_event_leased_until", columnList = "leased_until"),
            @Index(name = "idx_provider_event_ended", columnList = "ended_at"),
            @Index(name = "idx_provider_event_dead", columnList = "workspace_id, id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProviderEventRow {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    /** Null for an installation event, which arrives before any workspace exists. */
    @Column(name = "workspace_id")
    private @Nullable Long workspaceId;

    @Column(name = "provider", nullable = false, length = 16)
    private String provider;

    @Column(name = "subject", nullable = false, length = 512)
    private String subject;

    @Column(name = "scope_key", nullable = false, length = 600)
    private String scopeKey;

    @Column(name = "delivery_key", nullable = false)
    private String deliveryKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> headers;

    @Column(name = "payload", nullable = false)
    private byte[] payload;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "state", nullable = false, length = 8)
    private String state;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    /** Failed attempts whose failure is not known to be operational; they spend the poison budget. */
    @Column(name = "failures", nullable = false)
    private int failures;

    @Column(name = "lease_epoch", nullable = false)
    private int leaseEpoch;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "leased_until")
    private @Nullable Instant leasedUntil;

    @Column(name = "ended_at")
    private @Nullable Instant endedAt;

    @Column(name = "error_class")
    private @Nullable String errorClass;

    @Column(name = "replayed_at")
    private @Nullable Instant replayedAt;

    @Column(name = "replayed_by")
    private @Nullable Long replayedBy;
}
