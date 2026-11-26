package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.practices.spi.RawObservationAccess;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The work-place handoff of one sealed pull or merge request review (ADR 0121): the scoped copy that the composer of its
 * work place may read, and the producer that composes its copy on the work. The seal writes it in its own transaction,
 * once per review, and nothing changes it; {@link WorkPlaceHandoffRepository#enrol} is its only write.
 *
 * <p>The payload copies the review's public results and the parts of the work they cite, so it is person data. It is
 * erased with the review's jobs, exported without its payload, and deleted after {@code expires_at}.
 */
@Entity
@RawObservationAccess
@Table(
        name = "work_place_handoff",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_work_place_handoff_review",
                        columnNames = {"workspace_id", "review_id"}),
        indexes = {
            @Index(name = "ix_work_place_handoff_item", columnList = "workspace_id, item_id"),
            @Index(name = "ix_work_place_handoff_expiry", columnList = "expires_at")
        })
@Getter
public class WorkPlaceHandoffRow {

    /** Who composes the copy on the work of a review. It is decided at the seal and never changes. */
    public enum Producer {
        /** The review's own {@code REVIEW_COMPOSE} run. */
        LEGACY,
        /** A composer turn on the work place. */
        COMPOSER
    }

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "item_id", nullable = false)
    private long itemId;

    /** The reviewed head, or null when the review pinned no version. */
    @Column(name = "item_version", length = 1024)
    private @Nullable String itemVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "producer", nullable = false, length = 16)
    private Producer producer;

    @RawObservationAccess
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(name = "payload_digest", nullable = false, length = 64)
    private String payloadDigest;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
