package de.tum.cit.aet.hephaestus.agent.productfeedback;

import de.tum.cit.aet.hephaestus.agent.run.AgentRun;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunHandler;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunStopReason;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafts;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The shared run reaper moves the filing and its draft run in one transaction. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class DraftReportRuns implements AgentRunHandler {
    private final AgentRuns runs;
    private final ProductFeedbackDrafts drafts;

    @Override
    public AgentRunKind kind() {
        return AgentRunKind.DRAFT_REPORT;
    }

    @Override
    public int retries() {
        return 2;
    }

    @Override
    public void lock(AgentRunRef run) {
        subject(run).ifPresent(drafts::lock);
    }

    @Override
    public LossOutcome lost(AgentRunFence fence) {
        return new Requeue();
    }

    @Override
    public void ended(AgentRunRef run, int lastEpoch, AgentRunStopReason reason) {
        subject(run)
                .ifPresent(subject -> drafts.ended(subject, null, reason.name().toLowerCase(Locale.ROOT)));
    }

    private Optional<ProductFeedbackDrafts.Subject> subject(AgentRunRef run) {
        return runs.find(run).map(AgentRun::getConcurrencyKey).map(ProductFeedbackDrafts.Subject::fromKey);
    }
}
