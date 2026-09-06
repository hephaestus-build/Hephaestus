package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Schema(description = "Agent job execution record (job_token intentionally omitted)")
public record AgentJobDTO(
        @NonNull @Schema(description = "Job ID") UUID id,
        @NonNull @Schema(description = "Job type") AgentJobType jobType,
        @NonNull @Schema(description = "Current job status") AgentJobStatus status,

        @NonNull @Schema(description = "Work item reviewed by this job")
        ReviewRunTargetDTO target,

        @Schema(description = "Job metadata (routing/display info)") @Nullable
        Object metadata,

        @Schema(description = "Job output (agent results)") @Nullable
        Object output,

        @NonNull
        @Schema(
                description =
                        "Why a COMPLETED run produced the observations it did. INSUFFICIENT_EVIDENCE means no model "
                                + "ran because required evidence was missing, unreadable, stale, or unauthorized — so no observations "
                                + "means nothing was assessed, not that nothing was wrong. REVIEWED means the model ran against "
                                + "sufficient evidence.")
        ReviewRunOutcome reviewOutcome,

        @NonNull
        @Schema(
                description =
                        "Frozen agent config at submit time (an INSTANCE-scoped connection's baseUrl is redacted to scheme://host; only a WORKSPACE-scoped connection's baseUrl is left intact)")
        Object configSnapshot,

        @Schema(
                description =
                        "Upstream model this job was admitted on, frozen at submit time (e.g. gpt-5.4-mini). Available from submission, unlike llmModel, which the runner reports only once the job has run.")
        @Nullable
        String model,

        @Schema(description = "Container exit code") @Nullable
        Integer exitCode,

        @Schema(description = "Human-readable error message") @Nullable
        String errorMessage,

        @Schema(
                description =
                        "Delivery status: null = not applicable, PENDING = awaiting delivery, DELIVERED = posted, FAILED = delivery error")
        @Nullable
        DeliveryStatus deliveryStatus,

        @Schema(description = "Git provider comment/note ID for posted feedback") @Nullable
        String deliveryCommentId,

        @NonNull @Schema(description = "Number of retry attempts")
        Integer retryCount,

        @NonNull
        @Schema(
                description = "When this job becomes eligible to be claimed. In the future while the job is "
                        + "waiting — on a retry backoff, or on a hold. Read together with holdReason: a QUEUED job "
                        + "with availableAt in the future is waiting, not starved for workers.")
        Instant availableAt,

        @Schema(
                description = "Why a QUEUED job is waiting rather than eligible, when the reason is one an admin "
                        + "can undo. BUDGET = the payer is over its monthly LLM cap and the job resumes by itself once "
                        + "the cap is raised or the month rolls over. Absent means no such hold — a future availableAt "
                        + "is then an ordinary retry backoff.")
        @Nullable
        String holdReason,

        @NonNull @Schema(description = "Timestamp when the job was created")
        Instant createdAt,

        @Schema(description = "Timestamp when the job started running") @Nullable
        Instant startedAt,

        @Schema(description = "Timestamp when the job completed") @Nullable
        Instant completedAt,

        @Schema(description = "LLM model used (e.g. gpt-5.4-mini, openai/gpt-oss-120b)") @Nullable
        String llmModel,

        @Schema(
                description = "Model version/snapshot date (e.g. 2026-03-17). Only jobs from before the model catalog "
                        + "carry one; absent on everything newer.")
        @Nullable
        String llmModelVersion,

        @Schema(description = "Total LLM API calls (steps) during execution") @Nullable
        Integer llmTotalCalls,

        @Schema(description = "Total input tokens consumed") @Nullable
        Integer llmTotalInputTokens,

        @Schema(description = "Total output tokens generated") @Nullable
        Integer llmTotalOutputTokens,

        @Schema(description = "Total reasoning/thinking tokens") @Nullable
        Integer llmTotalReasoningTokens,

        @Schema(description = "Tokens read from prompt cache") @Nullable
        Integer llmCacheReadTokens,

        @Schema(description = "Tokens written to prompt cache") @Nullable
        Integer llmCacheWriteTokens) {
    public static AgentJobDTO from(AgentJob job) {
        JsonNode snapshot = job.getConfigSnapshot();
        return new AgentJobDTO(
                job.getId(),
                job.getJobType(),
                job.getStatus(),
                ReviewRunTargetDTO.from(job),
                job.getMetadata(),
                job.getOutput(),
                ReviewRunOutcome.fromJobOutput(job.getOutput()),
                redactInstanceBaseUrl(snapshot),
                snapshotString(snapshot, "upstreamModelId"),
                job.getExitCode(),
                job.getErrorMessage(),
                job.getDeliveryStatus(),
                job.getDeliveryCommentId(),
                job.getRetryCount(),
                job.getAvailableAt(),
                job.getHoldReason(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getLlmModel(),
                job.getLlmModelVersion(),
                job.getLlmTotalCalls(),
                job.getLlmTotalInputTokens(),
                job.getLlmTotalOutputTokens(),
                job.getLlmTotalReasoningTokens(),
                job.getLlmCacheReadTokens(),
                job.getLlmCacheWriteTokens());
    }

    /**
     * The listing's row, which carries every column this record renders and no transcript — the one
     * thing an entity page would have read per row and thrown away.
     */
    public static AgentJobDTO from(AgentJobRepository.AgentJobListRow row) {
        JsonNode snapshot = row.getConfigSnapshot();
        return new AgentJobDTO(
                row.getId(),
                row.getJobType(),
                row.getStatus(),
                ReviewRunTargetDTO.from(row),
                row.getMetadata(),
                row.getOutput(),
                ReviewRunOutcome.fromJobOutput(row.getOutput()),
                redactInstanceBaseUrl(snapshot),
                snapshotString(snapshot, "upstreamModelId"),
                row.getExitCode(),
                row.getErrorMessage(),
                row.getDeliveryStatus(),
                row.getDeliveryCommentId(),
                row.getRetryCount(),
                row.getAvailableAt(),
                row.getHoldReason(),
                row.getCreatedAt(),
                row.getStartedAt(),
                row.getCompletedAt(),
                row.getLlmModel(),
                row.getLlmModelVersion(),
                row.getLlmTotalCalls(),
                row.getLlmTotalInputTokens(),
                row.getLlmTotalOutputTokens(),
                row.getLlmTotalReasoningTokens(),
                row.getLlmCacheReadTokens(),
                row.getLlmCacheWriteTokens());
    }

    /**
     * The audience here is a workspace admin, who may see the full {@code baseUrl} only of a
     * {@code WORKSPACE}-scoped connection they configured themselves. Anything else — an INSTANCE
     * connection, or a scope-less snapshot from a rolling upgrade — is cut back to {@code scheme://host}
     * so an operator's gateway or deployment path cannot leak.
     */
    private static Object redactInstanceBaseUrl(JsonNode snapshot) {
        if (!(snapshot instanceof ObjectNode obj) || !obj.has("baseUrl")) {
            return snapshot;
        }
        if ("WORKSPACE".equals(snapshotString(snapshot, "connectionScope"))) {
            return snapshot;
        }
        ObjectNode redacted = obj.deepCopy();
        redacted.put("baseUrl", hostOnly(obj.path("baseUrl").asString(null)));
        return redacted;
    }

    private static String hostOnly(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return baseUrl;
        }
        try {
            URI uri = URI.create(baseUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            return scheme != null && host != null ? scheme + "://" + host : "(redacted)";
        } catch (IllegalArgumentException e) {
            return "(redacted)";
        }
    }

    private static @Nullable String snapshotString(JsonNode snapshot, String field) {
        if (snapshot == null || !snapshot.has(field) || snapshot.get(field).isNull()) {
            return null;
        }
        return snapshot.get(field).asString();
    }
}
