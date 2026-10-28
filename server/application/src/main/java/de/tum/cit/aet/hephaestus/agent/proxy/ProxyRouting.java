package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a validated proxy credential resolves to: the frozen, non-secret behaviour plus enough of a
 * connection reference to re-resolve the live credential. Never carries the credential itself.
 *
 * @param principalDescription log/metrics-safe identifier of the caller — never the token
 * @param attempt the execution this credential bills to
 * @param practiceScriptSlot the model for practice scripts this credential calls; {@code null} for the review's own
 *     calls and for every other gateway capability
 * @param practiceScriptTier the data handling tier of that model, as the attempt froze it; {@code null} without a
 *     practice script slot or when the attempt froze no tier for that model
 * @param workDeadline the signed end of the job attempt's work: every model call of the attempt ends by then. {@code
 *     null} for a mentor session, which has no such deadline and keeps the proxy's own call limits
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
        @Nullable ModelKind practiceScriptSlot,
        @Nullable DataHandlingTier practiceScriptTier,
        @Nullable Instant workDeadline) {
    public ProxyRouting {
        if (attempt != null && attempt.sourceType() != UsageSourceType.MENTOR_TURN && workDeadline == null)
            throw new IllegalArgumentException("A job route needs its attempt's work deadline");
    }

    /** Whether the call belongs to a Heph turn, which logs one line per model call with its turn. */
    boolean hephTurn() {
        return attempt.sourceType() == UsageSourceType.MENTOR_TURN;
    }

    /**
     * The one execution this credential bills to, with identity and spend read in the same instant so
     * a caller cannot pair one execution's identity with another's spend.
     *
     * @param sourceId the {@code review_job} id, or the {@code mentor_turn} id
     * @param number the row's {@code attempt} when the token was validated. Orphan recovery and the Heph turn lease
     *     reaper can requeue a row — zeroing its per-attempt accumulators — while a call this credential
     *     authenticated is still out, so a late call is settled on this attempt's own measurement, never on whoever
     *     owns the row now.
     * @param workerId worker owning the authenticated job attempt or Heph turn attempt
     * @param spentUsd priced with the rates frozen onto the execution at admission
     * @param directModelTokens the tokens that the attempt's direct model calls used on every slot, read with
     *     {@code spentUsd}; 0 for every other call
     * @param directModelSearchUnits the search units that the attempt's direct reranking calls billed, read with
     *     {@code spentUsd}; 0 for every other call
     * @param workspaceId the job's workspace when the call was authenticated; a review call is settled only in it.
     *     {@code null} for a mentor turn
     */
    public record BilledAttempt(
            UsageSourceType sourceType,
            UUID sourceId,
            int number,
            BigDecimal spentUsd,
            @Nullable String workerId,
            long directModelTokens,
            long directModelSearchUnits,
            @Nullable Long workspaceId) {

        public BilledAttempt(
                UsageSourceType sourceType, UUID sourceId, int number, BigDecimal spentUsd, @Nullable String workerId) {
            this(sourceType, sourceId, number, spentUsd, workerId, 0L, 0L, null);
        }

        public BilledAttempt(
                UsageSourceType sourceType,
                UUID sourceId,
                int number,
                BigDecimal spentUsd,
                @Nullable String workerId,
                long directModelTokens,
                long directModelSearchUnits) {
            this(sourceType, sourceId, number, spentUsd, workerId, directModelTokens, directModelSearchUnits, null);
        }
    }

    public UUID sourceId() {
        return attempt.sourceId();
    }

    /** Spend the ledger cannot see yet, because it is only appended when a run ends. */
    public BigDecimal inFlightSpendUsd() {
        return attempt.spentUsd();
    }
}
