package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.framework.RegisteredItemLabels;
import de.tum.cit.aet.hephaestus.integration.core.spi.MonitoredRepositoryQuery;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackVisibilityService;
import de.tum.cit.aet.hephaestus.practices.result.ProblemRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultAdmissionPolicy;
import de.tum.cit.aet.hephaestus.practices.result.ResultCommunicationRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultMarkRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultPresentation;
import de.tum.cit.aet.hephaestus.practices.result.ResultSourceAuthorization;
import de.tum.cit.aet.hephaestus.practices.result.ResultViewQuery;
import de.tum.cit.aet.hephaestus.practices.result.ResultVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustodyQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStart;
import java.time.Clock;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeRepositoryRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.MembershipReviewSelection;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class ReviewRuntimeRoleContextTest extends BaseUnitTest {
    private final ApplicationContextRunner context = withMocks(
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            ReviewSnapshotQueryService.class,
                            ReviewStartService.class,
                            PracticeReviewCoverageService.class,
                            ResultAdmissionPolicy.class,
                            ResultViewQuery.class,
                            ResultPresentation.class,
                            ResultVisibilityPolicy.class,
                            ProductReferenceQuery.class)
                    .withBean(Clock.class, Clock::systemUTC)
                    .withBean(JsonMapper.class, () -> JsonMapper.builder().build()),
            WorkspaceRepository.class,
            RepositoryToMonitorRepository.class,
            ReviewScopeRepositoryRepository.class,
            MonitoredRepositoryQuery.class,
            MembershipReviewSelection.class,
            HistoryCustodyQuery.class,
            PracticeRepository.class,
            ResultMarkRepository.class,
            ProblemRepository.class,
            EvidenceAuthorization.class,
            ResultSourceAuthorization.class,
            ProductItemRepository.class,
            RegisteredItemLabels.class,
            ItemDescriptorRegistry.class,
            ResultCommunicationRepository.class,
            FeedbackVisibilityService.class,
            ReviewRepository.class,
            ReviewPracticeRepository.class,
            PracticeVersionRepository.class,
            ProductItemQuery.class);

    // One withBean call per statement: NullAway infers a generic call chain through every receiver, so a long
    // chain of generic calls with lambdas grows its inference work exponentially.
    private static ApplicationContextRunner withMocks(ApplicationContextRunner runner, Class<?>... types) {
        var withMocks = runner;
        for (Class<?> type : types) {
            withMocks = withMock(withMocks, type);
        }
        return withMocks;
    }

    private static <T> ApplicationContextRunner withMock(ApplicationContextRunner runner, Class<T> type) {
        return runner.withBean(type, () -> mock(type));
    }

    @Test
    void shouldSupplyFrozenReadsOnTheWorkerWithoutTheServerCommandService() {
        context.withPropertyValues(
                        "hephaestus.runtime.server.enabled=false",
                        "hephaestus.runtime.worker.enabled=true",
                        "hephaestus.runtime.webhook.enabled=false")
                .run(app -> {
                    assertThat(app).hasSingleBean(ReviewSnapshotQuery.class);
                    assertThat(app).hasSingleBean(ReviewStart.class);
                    assertThat(app).hasSingleBean(ResultViewQuery.class);
                    assertThat(app).hasSingleBean(ResultPresentation.class);
                    assertThat(app).hasSingleBean(ResultVisibilityPolicy.class);
                    assertThat(app).hasSingleBean(ProductReferenceQuery.class);
                    assertThat(app).hasSingleBean(ResultAdmissionPolicy.class);
                    assertThat(app).doesNotHaveBean(PracticeReviewCoverageService.class);
                    assertThat(app).doesNotHaveBean(ReviewServiceImpl.class);
                });
    }

    @Test
    void shouldSupplyFrozenReadsOnTheServerWithoutAWorker() {
        context.withPropertyValues(
                        "hephaestus.runtime.server.enabled=true",
                        "hephaestus.runtime.worker.enabled=false",
                        "hephaestus.runtime.webhook.enabled=false")
                .run(app -> {
                    assertThat(app).hasSingleBean(ReviewSnapshotQuery.class);
                    assertThat(app).hasSingleBean(ReviewStart.class);
                    assertThat(app).hasSingleBean(PracticeReviewCoverageService.class);
                    assertThat(app).hasSingleBean(ResultAdmissionPolicy.class);
                });
    }

    @Test
    void shouldExcludeProductPreparationFromTheWebhookOnlyRole() {
        context.withPropertyValues(
                        "hephaestus.runtime.server.enabled=false",
                        "hephaestus.runtime.worker.enabled=false",
                        "hephaestus.runtime.webhook.enabled=true")
                .run(app -> {
                    assertThat(app).doesNotHaveBean(PracticeReviewCoverageService.class);
                    assertThat(app).doesNotHaveBean(ResultAdmissionPolicy.class);
                    assertThat(app).doesNotHaveBean(ReviewSnapshotQuery.class);
                    assertThat(app).doesNotHaveBean(ReviewStart.class);
                    assertThat(app).doesNotHaveBean(ResultViewQuery.class);
                    assertThat(app).doesNotHaveBean(ResultPresentation.class);
                    assertThat(app).doesNotHaveBean(ResultVisibilityPolicy.class);
                    assertThat(app).doesNotHaveBean(ProductReferenceQuery.class);
                });
    }
}
