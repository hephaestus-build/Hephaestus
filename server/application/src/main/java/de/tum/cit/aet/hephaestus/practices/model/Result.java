package de.tum.cit.aet.hephaestus.practices.model;

import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

@Entity
@Table(
        name = "result",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_result_workspace",
                    columnNames = {"id", "workspace_id"}),
            @UniqueConstraint(
                    name = "uk_result_assignment",
                    columnNames = {"id", "practice_id", "item_id", "workspace_id"}),
            @UniqueConstraint(
                    name = "uk_result_developer",
                    columnNames = {"id", "developer_id", "workspace_id"})
        })
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Result {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    @Column(name = "practice_id", nullable = false)
    private long practiceId;

    @Column(name = "practice_version_id")
    private @Nullable Long practiceVersionId;

    @Column(name = "item_id", nullable = false)
    private long itemId;

    @Column(name = "item_version", columnDefinition = "TEXT")
    private @Nullable String itemVersion;

    @Column(name = "developer_id")
    private @Nullable Long developerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 24)
    private ResultOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 16)
    private @Nullable Severity severity;

    @Column(name = "summary", length = 255)
    private @Nullable String summary;

    @Column(name = "explanation", columnDefinition = "TEXT")
    private @Nullable String explanation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", columnDefinition = "jsonb")
    private @Nullable JsonNode evidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "example", columnDefinition = "jsonb")
    private @Nullable JsonNode example;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "coverage", columnDefinition = "jsonb")
    private @Nullable JsonNode coverage;

    /**
     * Migrated history (ADR 0070 F-1): a read-only earlier review. It is never current and never delivered.
     * The {@code ck_result_evidence_version} constraint allows only this value and
     * {@link #CAPTURED_EVIDENCE_VERSION}. SQL that filters {@code evidence_version} names these constants.
     */
    public static final short MIGRATED_EVIDENCE_VERSION = 0;

    /** Evidence captured under the established capture contract. */
    public static final short CAPTURED_EVIDENCE_VERSION = 1;

    @Default
    @ColumnDefault("1")
    @Column(name = "evidence_version", nullable = false)
    private short evidenceVersion = CAPTURED_EVIDENCE_VERSION;

    /** True for migrated history, which stays read-only. */
    public boolean isMigratedHistory() {
        return evidenceVersion == MIGRATED_EVIDENCE_VERSION;
    }

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "ended_at")
    private @Nullable Instant endedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 24)
    private @Nullable ResultEndReason endReason;

    public void end(ResultEndReason reason, Instant at) {
        if (endedAt != null) {
            throw new IllegalStateException("Result already ended");
        }
        if (at.isBefore(recordedAt)) {
            throw new IllegalArgumentException("End time precedes result");
        }
        endedAt = at;
        endReason = reason;
    }
}
