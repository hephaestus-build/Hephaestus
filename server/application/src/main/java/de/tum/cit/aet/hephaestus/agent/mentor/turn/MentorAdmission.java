package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.MemberAiRoutingAdapter;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.mentor.MentorLlmConfig;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.exception.MentorRefusedException;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmAdmissionService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetBlockReason;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetExhaustedException;
import de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUnpricedUsageBlockedException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;

/**
 * Heph admission, the same for the app and Slack: the person's erasure fence, the member's AI choice, a model
 * within it, and the budget that model is paid from.
 */
@Service
@RequiredArgsConstructor
@NamedInterface(name = "mentor-turn")
public class MentorAdmission {

    private final MemberAiRoutingAdapter memberAiRouting;
    private final MemberAiPreferences memberAiPreferences;
    private final PersonProcessingSuppression personSuppression;
    private final LlmAdmissionService llmAdmissionService;
    private final LlmBudgetService llmBudgetService;
    private final MeterRegistry meters;

    /** Why Heph declines the developer, for a caller that must answer before it stores anything. */
    public Optional<MentorRefusal> refusal(long workspaceId, long developerId) {
        try {
            binding(workspaceId, developerId);
            return Optional.empty();
        } catch (MentorRefusedException refused) {
            return Optional.of(refused.reason());
        }
    }

    /**
     * The model and rates a turn of the developer runs with.
     *
     * @throws MentorRefusedException when Heph declines the developer
     * @throws LlmBudgetExhaustedException when the model's budget is used up
     * @throws LlmUnpricedUsageBlockedException when the budget policy blocks an unpriced model
     */
    public MentorLlmConfig admit(long workspaceId, long developerId) {
        WorkspaceAgentBinding binding = binding(workspaceId, developerId);
        MentorLlmConfig config = MentorLlmConfig.fromAdmission(binding, llmAdmissionService.admit(binding));
        FundingSource funding =
                Objects.requireNonNull(config.connectionScope(), "Mentor model must have a funding source");
        LlmBudgetBlockReason blocked = llmBudgetService.decide(workspaceId).forFunding(funding);
        if (blocked == LlmBudgetBlockReason.EXHAUSTED) {
            meters.counter(AgentMetrics.LLM_BUDGET_BLOCKED, "surface", "mentor").increment();
            throw new LlmBudgetExhaustedException(funding);
        }
        if (blocked == LlmBudgetBlockReason.UNPRICED_USAGE_BLOCKED) {
            meters.counter(AgentMetrics.LLM_BUDGET_BLOCKED, "surface", "mentor").increment();
            throw new LlmUnpricedUsageBlockedException(funding);
        }
        return config;
    }

    private WorkspaceAgentBinding binding(long workspaceId, long developerId) {
        if (personSuppression.isUserSuppressed(developerId)) {
            throw new MentorRefusedException(MentorRefusal.PERSON_ERASED);
        }
        return memberAiRouting
                .binding(workspaceId, AgentPurpose.MENTOR, developerId)
                .orElseThrow(() -> new MentorRefusedException(refusalWithoutModel(workspaceId, developerId)));
    }

    private MentorRefusal refusalWithoutModel(long workspaceId, long developerId) {
        MemberAiPreferences.Decision decision = memberAiPreferences.forDeveloper(workspaceId, developerId);
        if (decision.choice() == MemberAiChoice.NO_AI) return MentorRefusal.NO_AI;
        return decision.permitsAi() ? MentorRefusal.UNAVAILABLE : MentorRefusal.CHOICE_REQUIRED;
    }
}
