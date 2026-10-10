package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.ModelKind;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a validated proxy credential resolves to: the frozen, non-secret behaviour plus enough of a
 * connection reference to re-resolve the live credential. Never carries the credential itself.
 *
 * @param principalDescription log/metrics-safe identifier of the caller — never the token
 * @param attempt {@code null} only for a mentor session between turns
 * @param precomputeSlot the precompute model this credential calls; {@code null} for the review's own
 *     calls and for every other gateway capability
 * @param precomputeTier the data handling tier of that model, as the attempt froze it; {@code null} without a
 *     precompute slot or when the attempt froze no tier for that model
 */
public record ProxyRouting(
        String principalDescription,
        String apiProtocol,
        String baseUrl,
        @Nullable FundingSource connectionScope,
        @Nullable Long connectionId,
        @Nullable Long modelId,
        @Nullable Long workspaceId,
        @Nullable BilledAttempt attempt,
        @Nullable ModelKind precomputeSlot,
        @Nullable DataHandlingTier precomputeTier) {
    /**
     * The one execution this credential bills to, with identity and spend read in the same instant so
     * a caller cannot pair one execution's identity with another's spend.
     *
     * @param sourceId the {@code agent_job} id, or the assistant {@code chat_message} id of the turn
     * @param number the row's {@code retry_count} when the token was validated ({@code 0} for a mentor
     *     turn, which never retries). Orphan recovery can requeue an agent job — zeroing its per-attempt
     *     accumulators — while a call this credential authenticated is still out, so a late write must
     *     be dropped rather than billed to whoever owns the row now.
     * @param workerId worker owning the authenticated job attempt; null for mentor turns
     * @param spentUsd priced with the rates frozen onto the execution at admission
     * @param precomputeTokens the tokens that the attempt's precompute calls used on every model, read with
     *     {@code spentUsd}; 0 for every other call
     */
    public record BilledAttempt(
            LlmUsageSourceType sourceType,
            UUID sourceId,
            int number,
            BigDecimal spentUsd,
            @Nullable String workerId,
            long precomputeTokens) {

        /** An attempt that makes no precompute calls. */
        public BilledAttempt(
                LlmUsageSourceType sourceType,
                UUID sourceId,
                int number,
                BigDecimal spentUsd,
                @Nullable String workerId) {
            this(sourceType, sourceId, number, spentUsd, workerId, 0L);
        }
    }

    public @Nullable UUID sourceId() {
        return attempt == null ? null : attempt.sourceId();
    }

    /** Spend the ledger cannot see yet, because it is only appended when a run ends. */
    public BigDecimal inFlightSpendUsd() {
        return attempt == null ? BigDecimal.ZERO : attempt.spentUsd();
    }
}
