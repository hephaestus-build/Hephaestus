package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.agent.handler.PublicReviewEligibility;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRead;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryPolicy;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackGate;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackHistoryQueryService;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackListReadQuery;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackMarkListener;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPreparationStore;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPublicHistory;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackReadService;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSendQueue;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackStoreService;
import de.tum.cit.aet.hephaestus.practices.feedback.PreviousPracticePageFeedback;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackContentDigest;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecisionService;
import de.tum.cit.aet.hephaestus.practices.feedback.page.PracticePageFeedbackBasis;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("unit")
class FeedbackDeliveryRoleContextTest {
    private static final Set<Class<?>> CAPTURE_HELPERS = Set.of(
            PublicReviewEligibility.class,
            FeedbackStoreService.class,
            FeedbackSendQueue.class,
            FeedbackContentDigest.class,
            FeedbackGate.class,
            FeedbackHistoryQueryService.class,
            PreviousPracticePageFeedback.class,
            FeedbackDecisionService.class,
            FeedbackPreparationService.class,
            FeedbackCompositionContextService.class,
            PracticePageSupportReader.class,
            PracticePageFeedbackBasis.class,
            FeedbackLedgerRecorder.class,
            FeedbackDeliveryPolicy.class,
            FeedbackPreparationStore.class,
            FeedbackMarkListener.class,
            FeedbackPublicHistory.class,
            FeedbackResponseSuppressionFilter.class,
            RepeatedSummaryCheck.class);

    private static final Set<Class<?>> SERVER_READ_HELPERS =
            Set.of(FeedbackReadService.class, FeedbackBasisRead.class, FeedbackListReadQuery.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CAPTURE_HELPERS.toArray(Class<?>[]::new))
            .withUserConfiguration(ConversationFeedbackTtlPurger.class)
            .withUserConfiguration(SERVER_READ_HELPERS.toArray(Class<?>[]::new));

    @Test
    void shouldBootWebhookWithoutFeedbackPersistence() {
        runner.withPropertyValues(
                        RuntimeRole.SERVER_PROPERTY + "=false",
                        RuntimeRole.WORKER_PROPERTY + "=false",
                        RuntimeRole.WEBHOOK_PROPERTY + "=true")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(FeedbackRepository.class);
                    CAPTURE_HELPERS.forEach(type -> assertThat(context).doesNotHaveBean(type));
                    assertThat(context).doesNotHaveBean(ConversationFeedbackTtlPurger.class);
                    SERVER_READ_HELPERS.forEach(type -> assertThat(context).doesNotHaveBean(type));
                });
    }

    @ParameterizedTest
    @EnumSource(
            value = RuntimeRole.class,
            names = {"SERVER", "WORKER"})
    void shouldCreateActualPreparationAndHistoryHelpersOnCaptureRoles(RuntimeRole role) {
        var dependencies = new LinkedHashSet<Class<?>>();
        var helpers = new LinkedHashSet<>(CAPTURE_HELPERS);
        helpers.addAll(SERVER_READ_HELPERS);
        helpers.forEach(type -> Arrays.stream(type.getDeclaredConstructors()[0].getParameterTypes())
                .filter(dependency -> !helpers.contains(dependency))
                .forEach(dependencies::add));
        ApplicationContextRunner configured = runner;
        for (Class<?> dependency : dependencies) configured = withMock(configured, dependency);
        configured
                .withPropertyValues(
                        RuntimeRole.SERVER_PROPERTY + "=" + (role == RuntimeRole.SERVER),
                        RuntimeRole.WORKER_PROPERTY + "=" + (role == RuntimeRole.WORKER),
                        RuntimeRole.WEBHOOK_PROPERTY + "=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CAPTURE_HELPERS.forEach(type -> assertThat(context).hasSingleBean(type));
                    if (role == RuntimeRole.SERVER)
                        assertThat(context).hasSingleBean(ConversationFeedbackTtlPurger.class);
                    else assertThat(context).doesNotHaveBean(ConversationFeedbackTtlPurger.class);
                    SERVER_READ_HELPERS.forEach(type -> {
                        if (role == RuntimeRole.SERVER) assertThat(context).hasSingleBean(type);
                        else assertThat(context).doesNotHaveBean(type);
                    });
                });
    }

    private static <T> ApplicationContextRunner withMock(ApplicationContextRunner context, Class<T> type) {
        return context.withBean(type, () -> mock(type));
    }
}
