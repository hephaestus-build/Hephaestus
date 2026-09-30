package de.tum.cit.aet.hephaestus.agent.job;

import com.fasterxml.jackson.annotation.JsonIgnore;
import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.core.security.EncryptedStringConverter;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One container execution of a coding agent. Domain-agnostic: it knows the sandbox lifecycle, not PRs
 * or code. Domain-specific routing data lives in {@link #metadata} and results in {@link #output},
 * both shaped by the {@link AgentJobType} handler.
 */
@Entity
@Table(
        name = "agent_job",
        indexes = {
            @Index(name = "idx_agent_job_workspace_created", columnList = "workspace_id, created_at DESC, id DESC"),
            @Index(
                    name = "idx_agent_job_workspace_purpose_created",
                    columnList = "workspace_id, purpose, created_at DESC, id DESC"),
        },
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_agent_job_token",
                    columnNames = {"job_token"}),
            @UniqueConstraint(
                    name = "uk_agent_job_workspace_id",
                    columnNames = {"workspace_id", "id"}),
        })
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AgentJob {

    public static final String SIGNAL_REVISION_METADATA_KEY = "signal_revision";

    /** Slugs of the practices this review rechecks beside those its signal occasions, sorted. */
    public static final String RECHECKED_PRACTICES_METADATA_KEY = "rechecked_practices";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final int TOKEN_BYTES = 32;

    @Id
    @EqualsAndHashCode.Include
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false, foreignKey = @ForeignKey(name = "fk_agent_job_workspace"))
    @ToString.Exclude
    private Workspace workspace;

    /** Selects the workspace agent binding this job runs on; the executor re-admits it at claim time. */
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", length = 32)
    private AgentPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 50)
    private AgentJobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AgentJobStatus status = AgentJobStatus.QUEUED;

    /**
     * Which external system this job runs against; resolves the per-kind delivery channel. New rows MUST
     * set this at submit time — nullable on the column only because legacy rows are backfilled.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "integration_kind", length = 48)
    @Nullable
    private IntegrationKind integrationKind;

    /**
     * Discriminator for the work subject this job analyses; drives polymorphic delivery dispatch.
     * Nullable for legacy rows, which are backfilled as {@code scm.pull_request}.
     */
    @Column(name = "artifact_kind", length = ArtifactKind.MAX_LENGTH)
    @Nullable
    private ArtifactKind artifactKind;

    @ColumnDefault("0")
    @Column(name = "practice_rollout_revision", nullable = false)
    private Long practiceRolloutRevision = 0L;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'MANUAL'")
    @Column(name = "practice_trigger_mode", nullable = false, length = 24)
    private TriggerMode practiceTriggerMode = TriggerMode.MANUAL;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private @Nullable JsonNode metadata;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output", columnDefinition = "jsonb")
    private JsonNode output;

    /**
     * Agent config frozen at submit time, so a config change cannot alter an in-flight job. Contains the
     * provider base URL, which is why it is kept out of {@code toString()}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_snapshot", columnDefinition = "jsonb", nullable = false)
    @ToString.Exclude
    private JsonNode configSnapshot;

    @JsonIgnore
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "job_token", columnDefinition = "TEXT", nullable = false)
    @ToString.Exclude
    private String jobToken;

    @JsonIgnore
    @Column(name = "job_token_hash", length = 64)
    @ToString.Exclude
    private String jobTokenHash;

    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    @Column(name = "trace_id", nullable = false, updatable = false, length = 32)
    private String traceId;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_reason", length = 32)
    private AgentJobCancellationReason cancellationReason;

    @Column(name = "container_logs", columnDefinition = "TEXT")
    @ToString.Exclude
    private String containerLogs;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", length = 20)
    private DeliveryStatus deliveryStatus;

    @Column(name = "delivery_comment_id", length = 255)
    @Nullable
    private String deliveryCommentId;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    /**
     * When this job becomes eligible for a poll-loop claim; defaults to submit time. A requeue pushes it
     * into the future by {@link AgentJobBackoff#compute} so a crash-looping job backs off.
     */
    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    /** {@link #holdReason} value for a job held because its payer is over their monthly LLM cap. */
    public static final String HOLD_REASON_BUDGET = "BUDGET";

    /**
     * Why this QUEUED job's {@link #availableAt} was pushed into the future, when the reason is one an
     * admin can undo. It exists so raising a cap can release exactly those jobs
     * ({@link AgentJobRepository#releaseBudgetHolds}) without also fast-forwarding a crash-retry backoff
     * and hammering a failing upstream. Cleared on claim.
     */
    @Column(name = "hold_reason", length = 32)
    @Nullable
    private String holdReason;

    /**
     * Delivery-recovery attempts, distinct from {@link #retryCount}, which counts EXECUTION retries — a
     * COMPLETED job can have no execution retries left and still need several delivery attempts.
     */
    @ColumnDefault("0")
    @Column(name = "delivery_attempts", nullable = false)
    private short deliveryAttempts = 0;

    /**
     * When the conversational-feedback lane finished running for this job, whether or not it prepared
     * anything.
     *
     * <p>The lane is driven by an {@code @Async @TransactionalEventListener}, and a submission to a
     * saturated pool is rejected and gone: the event has no second chance and the work is lost with no
     * trace. This column is that trace. It is the only durable difference between "the lane ran and
     * decided nothing was worth preparing" and "the lane never ran", which are otherwise identical from
     * the outside — both are simply an absence of {@code feedback} rows.
     * {@code FeedbackLanePreparationSweeper} picks up whatever is still null.
     */
    @Column(name = "in_chat_prepared_at")
    private Instant inChatPreparedAt;

    /** The in-app lane's half of {@link #inChatPreparedAt}; the two lanes fail independently. */
    @Column(name = "in_app_prepared_at")
    private Instant inAppPreparedAt;

    /**
     * Worker that owns this job while RUNNING. Soft reference to {@code worker_registry.worker_id} (no
     * FK: a finished job must survive its worker row being reaped). Fences terminal writes, so a
     * requeued job's original worker cannot clobber the new owner's.
     */
    @Column(name = "worker_id", length = 255)
    @Nullable
    private String workerId;

    /**
     * Digest of the prompt scaffolding this run consumed. Equal digests ran byte-identical prompt
     * assembly, which is how an evaluation groups runs.
     */
    @Column(name = "prompt_digest", length = 64)
    private String promptDigest;

    /**
     * Digest over every file materialised into the sandbox workspace, with the job's own id elided so two
     * runs over identical work agree. The read-only repo mount is NOT hashed — its state is pinned by
     * {@code metadata.commit_sha}.
     */
    @Column(name = "inputs_digest", length = 64)
    private String inputsDigest;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_snapshot", columnDefinition = "jsonb")
    private JsonNode evidenceSnapshot;

    /**
     * The readiness decisions, kept out of {@link #evidenceSnapshot} because they are read in bulk. A
     * snapshot carries one entry per staged file and can reach megabytes; Postgres has no partial read
     * for a TOASTed jsonb, so reading one key out of a page of snapshots would detoast every one in full.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "review_readiness", columnDefinition = "jsonb")
    private JsonNode reviewReadiness;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    /**
     * Set immediately before sandbox/provider execution, after preparation. Null means the job cannot yet
     * have spent anything, so cancellation and recovery paths test this before recording LLM usage.
     */
    @Column(name = "execution_started_at")
    @Nullable
    private Instant executionStartedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    // LLM usage aggregates (populated at job completion from agent-reported usage)

    @Column(name = "llm_model", length = 100)
    @Nullable
    private String llmModel;

    /** Only carried by jobs frozen before the model catalog; the catalog identifies a model by id alone. */
    @Column(name = "llm_model_version", length = 50)
    @Nullable
    private String llmModelVersion;

    @Column(name = "llm_total_calls")
    @Nullable
    private Integer llmTotalCalls;

    @Column(name = "llm_total_input_tokens")
    @Nullable
    private Integer llmTotalInputTokens;

    @Column(name = "llm_total_output_tokens")
    @Nullable
    private Integer llmTotalOutputTokens;

    @Column(name = "llm_total_reasoning_tokens")
    @Nullable
    private Integer llmTotalReasoningTokens;

    @Column(name = "llm_cache_read_tokens")
    @Nullable
    private Integer llmCacheReadTokens;

    @Column(name = "llm_cache_write_tokens")
    @Nullable
    private Integer llmCacheWriteTokens;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.traceId == null) {
            this.traceId = UUID.randomUUID().toString().replace("-", "");
        }
        if (this.jobToken == null) {
            this.jobToken = generateJobToken();
        }
        if (this.jobTokenHash == null && this.jobToken != null) {
            this.jobTokenHash = computeTokenHash(this.jobToken);
        }
        if (this.status == null) {
            this.status = AgentJobStatus.QUEUED;
        }
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
        if (this.availableAt == null) {
            this.availableAt = this.createdAt;
        }
    }

    /**
     * Generate a fresh job token. Public so a requeue can rotate it without going through
     * {@code prePersist}: a partitioned-but-alive zombie sandbox must stop authenticating against the LLM
     * proxy the moment a sibling worker re-claims its job row.
     */
    public static String generateJobToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Indexed lookup key for a job token, since the encrypted column cannot be queried directly. */
    public static String computeTokenHash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
