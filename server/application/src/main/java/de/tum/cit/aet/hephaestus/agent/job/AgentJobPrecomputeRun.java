package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeRunStatus;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * What one practice's precompute script did in one attempt, as the runner's report said. The terminal write
 * of the attempt is the only writer, under the attempt's ownership fence, so a requeued attempt has no rows.
 *
 * <p>It holds no tokens or calls: a runner's count is never spend, and the LLM ledger owns spend.
 */
@Entity
@Table(
        name = "agent_job_precompute_run",
        indexes = {
            // The primary key leads with the attempt, so it cannot serve the job cascade.
            @Index(name = "idx_agent_job_precompute_run_job", columnList = "job_id"),
            @Index(
                    name = "idx_agent_job_precompute_run_practice",
                    columnList = "workspace_id, practice_slug, finished_at DESC"),
            // Serves the ON DELETE SET NULL of a deleted revision.
            @Index(name = "idx_agent_job_precompute_run_practice_revision", columnList = "practice_revision_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgentJobPrecomputeRun {

    @EmbeddedId
    private Id id;

    @MapsId("jobId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", foreignKey = @ForeignKey(name = "fk_agent_job_precompute_run_job"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AgentJob job;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "workspace_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_agent_job_precompute_run_workspace"))
    private Workspace workspace;

    /**
     * The practice revision the job admitted, from its evidence snapshot. A plain id: the practices module
     * owns the table ({@code sfk_agent_job_precompute_run_practice_revision}, {@code ON DELETE SET NULL}).
     */
    @Column(name = "practice_revision_id")
    private @Nullable Long practiceRevisionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PrecomputeRunStatus status;

    @Column(name = "leads", nullable = false)
    private int leads;

    /**
     * The models the script declared, each as a {@link StoredModelUse}; an empty array for a script that uses no
     * model. Null when they are not known: the script ended before it declared them, or did not finish.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "models", columnDefinition = "jsonb")
    private @Nullable JsonNode models;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    /** The first line of the script's error, as the runner reported it. Set only on {@code FAILED}. */
    @Column(name = "error", length = PiResultParser.PRECOMPUTE_ERROR_MAX_LENGTH)
    private @Nullable String error;

    /**
     * How long the script ran, in milliseconds, as the runner reported it. Null when the script did not run or did
     * not finish, or when the runner gave no count or a negative or malformed one.
     */
    @Column(name = "duration_ms")
    private @Nullable Integer durationMs;

    AgentJobPrecomputeRun(
            AgentJob job,
            int attempt,
            String practiceSlug,
            @Nullable Long practiceRevisionId,
            PrecomputeRunStatus status,
            int leads,
            @Nullable JsonNode models,
            Instant finishedAt,
            @Nullable String error,
            @Nullable Integer durationMs) {
        this.id = new Id(job.getId(), attempt, practiceSlug);
        this.job = job;
        this.workspace = job.getWorkspace();
        this.practiceRevisionId = practiceRevisionId;
        this.status = status;
        this.leads = leads;
        this.models = models;
        this.finishedAt = finishedAt;
        this.error = error;
        this.durationMs = durationMs;
    }

    public String practiceSlug() {
        return id.getPracticeSlug();
    }

    public int attempt() {
        return id.getAttempt();
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

        @Column(name = "practice_slug", nullable = false, length = 64)
        private String practiceSlug;
    }
}
