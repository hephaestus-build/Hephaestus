package de.tum.cit.aet.hephaestus.agent.usage;

import java.io.Serial;

/**
 * Thrown at an LLM enforcement point when the monthly cap of whoever pays for the call is reached. The
 * message is user-facing — it surfaces verbatim on the mentor channel, which is why the copy names the
 * admin who owns that purse's cap.
 *
 * <p>Deliberately not an HTTP exception: it is caught inside the mentor turn and never unwinds to a
 * controller, so a {@code @ResponseStatus} would suggest a status code no client can observe. An
 * enforcement point on a synchronous request path would need a mapping in {@code AgentControllerAdvice}.
 */
public class LlmBudgetExhaustedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public LlmBudgetExhaustedException(FundingSource fundingSource) {
        super(message(fundingSource));
    }

    private static String message(FundingSource fundingSource) {
        return fundingSource == FundingSource.WORKSPACE
                ? "The monthly AI cap of this workspace is reached. Work pauses until next month, unless a "
                        + "workspace admin raises the cap."
                : "The monthly AI budget of this workspace is reached. Work pauses until next month, unless "
                        + "an instance admin raises the budget.";
    }
}
