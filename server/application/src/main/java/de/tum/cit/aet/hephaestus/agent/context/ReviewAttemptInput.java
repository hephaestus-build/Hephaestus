package de.tum.cit.aet.hephaestus.agent.context;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

/**
 * One file of a running attempt's folder as its capture staged it, except the checkouts: those a server builds again
 * from the recorded commit and tree. Any server rebuilds the attempt's folder from these rows, so any server can serve
 * the attempt's sandbox and admit its results ({@link JobEvidenceFiles#ensureAttemptFolder}).
 *
 * <p>The rows live only while their attempt runs: the reconcile deletes those of an attempt that is not its job's
 * running one, and erasure deletes those of an erased capture. They are scoped to the job's workspace and removed with
 * the job. Written and read with SQL only.
 */
@Entity
@Table(
        name = "review_attempt_input",
        indexes = @Index(name = "idx_review_attempt_input_job", columnList = "workspace_id,job_id,attempt"),
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_review_attempt_input_path",
                        columnNames = {"job_id", "attempt", "run_id", "path"}))
@Getter
@Setter
public class ReviewAttemptInput {
    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(nullable = false)
    private int attempt;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    /** The file's path in the attempt's folder. */
    @Column(nullable = false, length = 1024)
    private String path;

    /** The SHA-256 of {@link #content}, checked before a server writes the file. */
    @Column(nullable = false, length = 64)
    private String sha256;

    /** The frozen POSIX permissions; an executable practice program must stay executable on every server. */
    @Column(nullable = false, length = 9)
    private String permissions;

    @Column(nullable = false)
    private byte[] content;

    @ColumnDefault("now()")
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;
}
