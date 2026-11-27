package de.tum.cit.aet.hephaestus.practices.advice;

import de.tum.cit.aet.hephaestus.practices.spi.RawObservationAccess;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/**
 * One immutable revision of an advice entry. Its text is written from observations, so a conversation reads it only
 * through the advice reads. The text becomes NULL, and {@code staleAt} is set, when evidence behind it is deleted.
 */
@Entity
@Immutable
@RawObservationAccess
@Table(
        name = "practice_advice_revision",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_practice_advice_revision_number",
                    columnNames = {"advice_id", "number"}),
            @UniqueConstraint(
                    name = "uk_practice_advice_revision_owner",
                    columnNames = {"id", "person_account_id", "workspace_id"})
        },
        indexes =
                @Index(
                        name = "idx_practice_advice_revision_advice",
                        columnList = "advice_id, person_account_id, workspace_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PracticeAdviceRevision {

    @Id
    @RawObservationAccess.Metadata
    @Column(name = "id", nullable = false, columnDefinition = "UUID")
    private UUID id;

    @RawObservationAccess.Metadata
    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @RawObservationAccess.Metadata
    @Column(name = "person_account_id", nullable = false)
    private long personAccountId;

    @RawObservationAccess.Metadata
    @Column(name = "advice_id", nullable = false, columnDefinition = "UUID")
    private UUID adviceId;

    @RawObservationAccess.Metadata
    @Column(name = "number", nullable = false)
    private int number;

    @Column(name = "text", columnDefinition = "TEXT")
    private @Nullable String text;

    @RawObservationAccess.Metadata
    @Column(name = "review_id", nullable = false, columnDefinition = "UUID")
    private UUID reviewId;

    @RawObservationAccess.Metadata
    @Column(name = "cited_count", nullable = false)
    private int citedCount;

    /** The sources at write time. A revision with fewer live sources is hidden, whatever deleted them. */
    @RawObservationAccess.Metadata
    @Column(name = "source_count", nullable = false)
    private int sourceCount;

    @RawObservationAccess.Metadata
    @Column(name = "written_at", nullable = false)
    private Instant writtenAt;

    @RawObservationAccess.Metadata
    @Column(name = "stale_at")
    private @Nullable Instant staleAt;
}
