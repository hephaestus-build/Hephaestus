package de.tum.cit.aet.hephaestus.mentor.runtime;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a model call presents for one leased attempt: the claim it holds and the route it was granted on. The dispatch
 * fence matches every field against the stored attempt, so a credential for another account, claim or route proves
 * nothing.
 */
public record AttemptCredential(
        long accountId,
        UUID attemptId,
        long leaseEpoch,
        UUID leaseToken,
        long modelId,
        long connectionId,
        PayerKind payer,
        @Nullable Long payerWorkspaceId) {

    /** Who pays for the attempt's calls: a workspace for a Slack route, the instance purse for a personal one. */
    public enum PayerKind {
        WORKSPACE,
        PERSONAL_PURSE
    }
}
