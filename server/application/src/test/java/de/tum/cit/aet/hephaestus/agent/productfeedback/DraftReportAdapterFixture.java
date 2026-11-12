package de.tum.cit.aet.hephaestus.agent.productfeedback;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.proxy.ProductFeedbackModelGateway;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.core.action.ActionAdapter;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDraftCalls;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafts;
import java.time.Clock;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/** Runs the production adapter against a test-owned proxy without starting the sandbox runtime. */
public final class DraftReportAdapterFixture {
    private DraftReportAdapterFixture() {}

    public static ActionAdapter adapter(
            ProductFeedbackDrafts drafts,
            ProductFeedbackDraftCalls calls,
            LlmModelResolver models,
            ProductFeedbackModelGateway gateway,
            ObjectMapper mapper,
            LlmUsageRecorder usage) {
        return new DraftReportAdapter(drafts, calls, models, Optional.of(gateway), mapper, Clock.systemUTC(), usage);
    }
}
