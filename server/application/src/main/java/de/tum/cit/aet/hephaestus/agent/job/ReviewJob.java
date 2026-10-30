package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
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
import java.util.Objects;
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

@Entity
@Table(
        name = "review_job",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_review_job_workspace",
                        columnNames = {"id", "workspace_id"}))
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ReviewJob {
    public static final String HOLD_REASON_BUDGET = "BUDGET";

    /** How many drains a review survives; the next one cancels it with the drain's reason. */
    public static final int MAX_DRAINS = 3;
    /** The sandbox runtime had no room for the attempt's sandbox; nothing of it ran, so it spent no retry. */
    public static final String HOLD_REASON_SANDBOX_ROOM = "SANDBOX_ROOM";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'QUEUED'")
    @Column(name = "status", nullable = false, length = 20)
    private ReviewJobStatus status = ReviewJobStatus.QUEUED;

    @ColumnDefault("0")
    @Column(name = "attempt", nullable = false)
    private int attempt = 0;

    /** The attempts that a worker's drain stopped: each ran, so each has its own number, but none spends a retry. */
    @ColumnDefault("0")
    @Column(name = "drain_count", nullable = false)
    private int drainCount = 0;

    /** The attempts whose sandbox the runtime had no room for: each has its own number, but none spends a retry. */
    @ColumnDefault("0")
    @Column(name = "room_refusals", nullable = false)
    private int roomRefusals = 0;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'NONE'")
    @Column(name = "processing_status", nullable = false, length = 16)
    private ProcessingStatus processingStatus = ProcessingStatus.NONE;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "hold_reason", length = 32)
    private @Nullable String holdReason;

    @Column(name = "worker_id", length = 255)
    private @Nullable String workerId;

    @Column(name = "trace_id", nullable = false, length = 32)
    private String traceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input", nullable = false, columnDefinition = "jsonb")
    private ReviewJobInput input;

    @JdbcTypeCode(SqlTypes.JSON)
    @ToString.Exclude
    @Column(name = "config_snapshot", nullable = false, columnDefinition = "jsonb")
    private JsonNode configSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @ToString.Exclude
    @Column(name = "capture", columnDefinition = "jsonb")
    private @Nullable JsonNode capture;

    @JdbcTypeCode(SqlTypes.JSON)
    @ToString.Exclude
    @Column(name = "output", columnDefinition = "jsonb")
    private @Nullable JsonNode output;

    @Column(name = "exit_code")
    private @Nullable Integer exitCode;

    @ToString.Exclude
    @Column(name = "error_message", columnDefinition = "text")
    private @Nullable String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_reason", length = 32)
    private @Nullable ReviewJobCancellationReason cancellationReason;

    @Column(name = "conversation_prepared_at")
    private @Nullable Instant conversationPreparedAt;

    @Column(name = "practice_page_prepared_at")
    private @Nullable Instant practicePagePreparedAt;

    @Column(name = "public_review_digest", length = 64)
    private @Nullable String publicReviewDigest;

    @Column(name = "admission_attempt")
    private @Nullable Integer admissionAttempt;

    @Column(name = "admission_digest", length = 64)
    private @Nullable String admissionDigest;

    @ColumnDefault("0")
    @Column(name = "llm_calls", nullable = false)
    private int llmCalls = 0;

    @ColumnDefault("0")
    @Column(name = "llm_input_tokens", nullable = false)
    private long llmInputTokens = 0;

    @ColumnDefault("0")
    @Column(name = "llm_output_tokens", nullable = false)
    private long llmOutputTokens = 0;

    @ColumnDefault("0")
    @Column(name = "llm_reasoning_tokens", nullable = false)
    private long llmReasoningTokens = 0;

    @ColumnDefault("0")
    @Column(name = "llm_cache_read_tokens", nullable = false)
    private long llmCacheReadTokens = 0;

    @ColumnDefault("0")
    @Column(name = "llm_cache_write_tokens", nullable = false)
    private long llmCacheWriteTokens = 0;

    @Column(name = "started_at")
    private @Nullable Instant startedAt;

    /**
     * Until when the worker that runs this attempt holds it, on the database clock. The worker sets it at the claim and
     * renews it with its heartbeat. An attempt whose lease expired lost its worker, and the server recovers it.
     */
    @Column(name = "leased_until")
    private @Nullable Instant leasedUntil;

    @Column(name = "execution_started_at")
    private @Nullable Instant executionStartedAt;

    @Column(name = "completed_at")
    private @Nullable Instant completedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (traceId == null) traceId = UUID.randomUUID().toString().replace("-", "");
        if (submittedAt == null) submittedAt = Instant.now();
        if (availableAt == null) availableAt = submittedAt;
        Objects.requireNonNull(reviewId, "reviewId");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(configSnapshot, "configSnapshot");
        if (workspaceId <= 0) throw new IllegalArgumentException("Workspace id must be positive");
        if (input.assignments().stream().anyMatch(assignment -> !reviewId.equals(assignment.reviewId()))) {
            throw new IllegalArgumentException("Job assignments belong to another review");
        }
    }

    public static String generateJobToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String computeTokenHash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 not available", error);
        }
    }

    /** The attempts that spent a retry: those that a worker's drain stopped, or that found no sandbox room, did not. */
    public int spentAttempts() {
        return attempt - drainCount - roomRefusals;
    }
}
