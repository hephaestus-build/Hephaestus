package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.job.ReviewAttemptUsageService;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnAdmission;
import de.tum.cit.aet.hephaestus.agent.usage.RunningSpend;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * What a workspace's running reviews and conversation turns have spent so far, at the rates frozen for each of them.
 * A call counts here once it has settled, so a call still at its provider does not, and neither does a model without a
 * price: its spend stays unknown until the run ends and the ledger marks it.
 */
@Component
@ConditionalOnWorkerRole
class ProxyRunningSpend {

    private final ReviewAttemptUsageService attempts;
    private final MentorTurnRepository turns;

    ProxyRunningSpend(ReviewAttemptUsageService attempts, MentorTurnRepository turns) {
        this.attempts = attempts;
        this.turns = turns;
    }

    RunningSpend of(long workspaceId) {
        RunningSpend spend = RunningSpend.NONE;
        for (var review : attempts.runningSpend(workspaceId)) {
            spend = spend.plus(UsageFundingSource.valueOf(review.fundingSource()), review.usd());
        }
        for (var turn : turns.findRunningUsage(workspaceId)) {
            var admission = MentorTurnAdmission.read(turn.admission()).orElse(null);
            if (admission == null) continue;
            BigDecimal usd = admission
                    .price()
                    .calculateCost(
                            turn.inputTokens(), turn.outputTokens(), turn.cachedTokens(), turn.cacheWriteTokens())
                    .usd();
            if (usd != null) spend = spend.plus(admission.price().fundingSource(), usd);
        }
        return spend;
    }
}
