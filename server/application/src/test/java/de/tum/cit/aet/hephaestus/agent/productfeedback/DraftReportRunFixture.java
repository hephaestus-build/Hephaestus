package de.tum.cit.aet.hephaestus.agent.productfeedback;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDraftCalls;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafts;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/** The production run kind on the test server, without turning on its Kubernetes client or background dispatch. */
@TestConfiguration(proxyBeanMethods = false)
public class DraftReportRunFixture {
    @Bean
    DraftReportSandboxRun draftReportSandboxRunForTest(ApplicationContext beans) {
        return create(beans);
    }

    public static SandboxRunKind kind(ApplicationContext beans) {
        return create(beans);
    }

    private static DraftReportSandboxRun create(ApplicationContext beans) {
        return new DraftReportSandboxRun(
                beans.getBean(AgentRuns.class),
                beans.getBean(DraftReportRuns.class),
                beans.getBean(ProductFeedbackDrafts.class),
                beans.getBean(ProductFeedbackDraftCalls.class),
                beans.getBean(LlmModelResolver.class),
                beans.getBean(ObjectMapper.class),
                beans.getBean(LlmUsageRecorder.class));
    }
}
