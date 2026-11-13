package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.ModelLimits;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a validated proxy credential resolves to: the frozen, non-secret behaviour plus enough of a
 * connection reference to re-resolve the live credential. Never carries the credential itself.
 *
 * @param principalDescription log/metrics-safe identifier of the caller — never the token
 * @param attempt the execution this credential bills to
 * @param workDeadline the signed end of the job attempt's work: every model call of the attempt ends by then. {@code
 *     null} for a conversation turn, whose calls its turn ceiling bounds instead
 * @param outputLimit the output limit {@code L} of the call's frozen model ({@link #outputLimitOf}, {@link
 *     OutputLimit}). {@code null} when the model froze neither limit
 */
public record ProxyRouting(
        String principalDescription,
        String apiProtocol,
        String baseUrl,
        @Nullable UsageFundingSource connectionScope,
        @Nullable Long connectionId,
        @Nullable Long modelId,
        @Nullable Long workspaceId,
        BilledAttempt attempt,
        @Nullable Instant workDeadline,
        @Nullable Long outputLimit,
        @Nullable UUID actionId) {
    public ProxyRouting(
            String principalDescription,
            String apiProtocol,
            String baseUrl,
            @Nullable UsageFundingSource connectionScope,
            @Nullable Long connectionId,
            @Nullable Long modelId,
            @Nullable Long workspaceId,
            BilledAttempt attempt,
            @Nullable Instant workDeadline,
            @Nullable Long outputLimit) {
        this(
                principalDescription,
                apiProtocol,
                baseUrl,
                connectionScope,
                connectionId,
                modelId,
                workspaceId,
                attempt,
                workDeadline,
                outputLimit,
                null);
    }

    public ProxyRouting {
        if (attempt != null && attempt.sourceType() != UsageSourceType.MENTOR_TURN && workDeadline == null)
            throw new IllegalArgumentException("A job route needs its attempt's work deadline");
        if (outputLimit != null && outputLimit <= 0)
            throw new IllegalArgumentException("An output limit must be positive");
    }

    /**
     * The output limit {@code L} of a model: its {@code maxOutputTokens}, else the maximum output derived from its {@code
     * contextWindow} ({@link ModelLimits#derivedMaxOutputTokens}). A route frozen before the model's limits were resolved
     * may carry only the context window.
     */
    public static @Nullable Long outputLimitOf(@Nullable Integer contextWindow, @Nullable Integer maxOutputTokens) {
        Integer limit = maxOutputTokens != null && maxOutputTokens > 0
                ? maxOutputTokens
                : ModelLimits.derivedMaxOutputTokens(contextWindow);
        return limit == null || limit <= 0 ? null : limit.longValue();
    }

    /** Whether the call belongs to a Heph turn, which logs one line per model call with its turn. */
    boolean hephTurn() {
        return attempt.sourceType() == UsageSourceType.MENTOR_TURN;
    }

    /**
     * The one execution this credential bills to.
     *
     * @param sourceId the {@code review_job} id, or the {@code mentor_turn} id
     * @param number the row's {@code attempt} when the token was validated. The run reaper can requeue a row while a
     *     call this credential authenticated is still out, so the call stays this attempt's, never that of whoever owns
     *     the row now.
     * @param runId the run whose claim authenticated this attempt
     * @param workspaceId the job's workspace when the call was authenticated. {@code null} for a mentor turn
     */
    public record BilledAttempt(
            UsageSourceType sourceType,
            UUID sourceId,
            int number,
            @Nullable UUID runId,
            @Nullable Long workspaceId) {

        public BilledAttempt(UsageSourceType sourceType, UUID sourceId, int number, @Nullable UUID runId) {
            this(sourceType, sourceId, number, runId, null);
        }

        /** The claim of the run that this attempt holds: a turn's attempt is its epoch, a review's is one less. */
        public int epoch() {
            return sourceType == UsageSourceType.MENTOR_TURN ? number : ReviewRunKind.epoch(number);
        }
    }

    public UUID sourceId() {
        return attempt.sourceId();
    }
}
