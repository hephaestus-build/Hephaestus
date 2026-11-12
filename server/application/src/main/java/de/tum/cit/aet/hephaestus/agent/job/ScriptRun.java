package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.practices.spi.ScriptKind;
import de.tum.cit.aet.hephaestus.practices.spi.ScriptRunStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ScriptStopReason;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

@Entity
@Table(
        name = "review_job_script_run",
        indexes = @Index(name = "idx_review_job_script_run_job_attempt", columnList = "job_id,attempt,practice_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScriptRun {
    @EmbeddedId
    private Id id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "practice_version_id", nullable = false)
    private long practiceVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ScriptRunStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "script_kind", nullable = false, length = 16)
    private ScriptKind scriptKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "stop_reason", length = 32)
    private @Nullable ScriptStopReason stopReason;

    /**
     * The key in {@link #calls} of the runner's own review-session counts ({@code modelCalls},
     * {@code evidenceRefusals}, {@code formatRefusals}). Runs recorded before the runner counted them lack it.
     */
    public static final String REVIEW_SESSIONS = "reviewSessions";

    /** The host's calls by kind, and under {@link #REVIEW_SESSIONS} the runner's review-session counts. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "calls", columnDefinition = "jsonb")
    private @Nullable JsonNode calls;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    @Column(name = "error", length = 500)
    private @Nullable String error;

    @Column(name = "duration_ms")
    private @Nullable Integer durationMs;

    public ScriptRun(
            UUID jobId,
            int attempt,
            long practiceId,
            long workspaceId,
            long practiceVersionId,
            ScriptRunStatus status,
            ScriptKind scriptKind,
            @Nullable ScriptStopReason stopReason,
            @Nullable JsonNode calls,
            Instant finishedAt,
            @Nullable String error,
            @Nullable Integer durationMs) {
        this.id = new Id(jobId, attempt, practiceId);
        this.workspaceId = workspaceId;
        this.practiceVersionId = practiceVersionId;
        this.status = status;
        this.scriptKind = scriptKind;
        this.stopReason = stopReason;
        this.calls = calls == null ? null : calls.deepCopy();
        this.finishedAt = finishedAt;
        this.error = error;
        this.durationMs = durationMs;
        if (workspaceId <= 0 || practiceId <= 0 || practiceVersionId <= 0 || attempt < 0) {
            throw new IllegalArgumentException("Invalid script execution identity");
        }
        if (durationMs != null && durationMs < 0) throw new IllegalArgumentException("Invalid script duration");
        if (error != null && error.length() > 500) throw new IllegalArgumentException("Script error is too long");
    }

    @Embeddable
    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Id implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        @Column(name = "job_id", nullable = false)
        private UUID jobId;

        @Column(name = "attempt", nullable = false)
        private int attempt;

        @Column(name = "practice_id", nullable = false)
        private long practiceId;
    }
}
