package de.tum.cit.aet.hephaestus.practices.advice;

import de.tum.cit.aet.hephaestus.practices.spi.AdviceRetireReason;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/** One advice entry for a workspace, a developer and a practice. Only the developer reads it. */
@Entity
@Table(
        name = "practice_advice",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_practice_advice_practice",
                    columnNames = {"workspace_id", "person_account_id", "practice_id"}),
            @UniqueConstraint(
                    name = "uk_practice_advice_owner",
                    columnNames = {"id", "person_account_id", "workspace_id"})
        },
        indexes = {
            @Index(name = "idx_practice_advice_person", columnList = "person_account_id"),
            @Index(name = "idx_practice_advice_practice", columnList = "practice_id, workspace_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PracticeAdvice {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "UUID")
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "person_account_id", nullable = false)
    private long personAccountId;

    @Column(name = "practice_id", nullable = false)
    private long practiceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private AdviceState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "retire_reason", length = 24)
    private @Nullable AdviceRetireReason retireReason;

    @Column(name = "retired_at")
    private @Nullable Instant retiredAt;

    @Column(name = "dismissed_at")
    private @Nullable Instant dismissedAt;

    /** The items that the dismissed revision cited, kept so that a dismissal outlives its revision's sources. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "dismissed_items", columnDefinition = "bigint[]")
    private long @Nullable [] dismissedItems;

    /** Raised by every revise and retire, so a writer that saw an older state is refused. */
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "latest_revision")
    private @Nullable Integer latestRevision;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
