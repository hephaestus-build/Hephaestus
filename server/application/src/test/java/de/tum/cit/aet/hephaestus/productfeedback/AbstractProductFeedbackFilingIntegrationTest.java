package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.agent.productfeedback.DraftReportRunFixture;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssueUpdates;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ProductFeedbackIssues;
import de.tum.cit.aet.hephaestus.notification.email.CapturingMailTestConfiguration;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Report filing with the real draft run, captured email and a test-controlled GitHub destination. */
@Import({CapturingMailTestConfiguration.class, DraftReportRunFixture.class})
@TestPropertySource(properties = "hephaestus.email.from=noreply@hephaestus.test")
abstract class AbstractProductFeedbackFilingIntegrationTest extends BaseIntegrationTest {
    // One adapter implements both interfaces. Replace both so no call can reach the provider.
    @MockitoBean
    protected ProductFeedbackIssues issues;

    @MockitoBean
    protected ProductFeedbackIssueUpdates updates;
}
