package de.tum.cit.aet.hephaestus.agent.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.proxy.LlmCallPermits;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetDecision;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStart;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The claim and the terminal writes of a sandbox's review run, as the worker role builds them, from a test context in
 * which the worker role is off. The model catalog is left out, so a claim keeps the model its job froze, and the
 * budget allows every claim.
 */
public final class SandboxReviewClaims {
    private SandboxReviewClaims() {}

    /** @param policy whether the developer allows AI reviews, and the binding that the claim checks the model against */
    public static ReviewJobClaimService claimService(ApplicationContext context, ReviewMemberAiPolicy policy) {
        var budgets = mock(LlmBudgetService.class);
        when(budgets.decide(anyLong())).thenReturn(LlmBudgetDecision.ALLOWED);
        var calls = mock(LlmCallPermits.class);
        when(calls.poolFor(any())).thenReturn(1L);
        return new ReviewJobClaimService(
                context.getBean(ReviewJobRepository.class),
                policy,
                mock(ReviewMethodVerifier.class),
                context.getBean(ReviewJobAssignments.class),
                context.getBean(TransactionTemplate.class),
                context.getBean(JsonMapper.class),
                context.getBean(ReviewStart.class),
                budgets,
                null,
                context.getBean(ReviewJobTelemetry.class),
                calls,
                context.getBean(AgentRuns.class),
                context.getBean(ReviewRunKind.class));
    }

    public static ReviewJobTerminal terminal(ApplicationContext context) {
        return new ReviewJobTerminal(
                context.getBean(ReviewJobRepository.class),
                context.getBean(AgentRuns.class),
                context.getBean(ReviewAttemptUsageService.class),
                context.getBean(JsonMapper.class),
                null,
                context.getBean(ReviewCompositionContinuation.class));
    }
}
