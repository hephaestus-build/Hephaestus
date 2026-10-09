package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a validated proxy credential resolves to: the frozen, non-secret behaviour plus enough of a
 * connection reference to re-resolve the live credential. Never carries the credential itself.
 *
 * @param principalDescription log/metrics-safe identifier of the caller — never the token
 * @param attempt the execution this credential bills to
 */
public record ProxyRouting(
        String principalDescription,
        String apiProtocol,
        String baseUrl,
        @Nullable FundingSource connectionScope,
        @Nullable Long connectionId,
        @Nullable Long modelId,
        @Nullable Long workspaceId,
        BilledAttempt attempt) {
    /**
     * The one execution this credential bills to, with identity and spend read in the same instant so
     * a caller cannot pair one execution's identity with another's spend.
     *
     * @param sourceId the {@code agent_job} id, or the {@code mentor_turn} id
     * @param number the row's {@code retry_count} when the token was validated, or the Heph turn's
     *     {@code attempt}. Orphan recovery and the mentor lease reaper can requeue a row — zeroing its
     *     per-attempt accumulators — while a call this credential authenticated is still out, so a late
     *     write must be dropped rather than billed to whoever owns the row now.
     * @param workerId worker owning the authenticated job attempt or Heph turn attempt
     * @param spentUsd priced with the rates frozen onto the execution at admission
     */
    public record BilledAttempt(
            LlmUsageSourceType sourceType, UUID sourceId, int number, BigDecimal spentUsd, String workerId) {}

    public UUID sourceId() {
        return attempt.sourceId();
    }

    /** Spend the ledger cannot see yet, because it is only appended when a run ends. */
    public BigDecimal inFlightSpendUsd() {
        return attempt.spentUsd();
    }
}
