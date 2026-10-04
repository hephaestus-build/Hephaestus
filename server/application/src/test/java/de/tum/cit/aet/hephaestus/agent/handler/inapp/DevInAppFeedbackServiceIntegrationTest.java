package de.tum.cit.aet.hephaestus.agent.handler.inapp;

import static de.tum.cit.aet.hephaestus.practices.model.Outcome.NOT_MET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.DevPracticeRevisionController;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackThreadKey;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackUsefulness;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.server.ResponseStatusException;

/**
 * The dev seed's cards are the cards the page reads: written with a history, they show on the developer's page
 * as unread, or as read and answered, exactly as a prepared and answered card would.
 */
class DevInAppFeedbackServiceIntegrationTest extends AbstractPracticeReviewIntegrationTest {

    private static final Instant PREPARED_AT = NOW.minus(Duration.ofDays(10));

    @Autowired
    private ApplicationContext context;

    private Workspace workspace;
    private User developer;
    private User teammate;
    private Practice scoping;
    private Practice testing;
    private AgentJob run;
    private UUID scopingProblem;
    private UUID testingProblem;

    @BeforeEach
    void seedReview() {
        User owner = persistUser("dev-seed-owner");
        workspace = createWorkspace("dev-seed-ws", "Dev Seed WS", "dev-seed-org", AccountType.ORG, owner);
        developer = persistUser("testuser"); // matches @WithUser
        teammate = persistUser("teammate");
        ensureWorkspaceMembership(workspace, developer, WorkspaceMembership.WorkspaceRole.MEMBER);
        ensureWorkspaceMembership(workspace, teammate, WorkspaceMembership.WorkspaceRole.MEMBER);
        scoping = persistPractice(workspace, null, "scope-one-reviewable-change", "Scope one change", null);
        testing = persistPractice(workspace, null, "ships-tests-with-the-change", "Ship tests", null);
        run = persistPullRequestReview(workspace, 10, PREPARED_AT.minus(Duration.ofHours(1)));
        scopingProblem =
                observe(scoping, run, 10L, developer, NOT_MET, Severity.MINOR, PREPARED_AT.minus(Duration.ofHours(1)));
        testingProblem =
                observe(testing, run, 10L, developer, NOT_MET, Severity.MINOR, PREPARED_AT.minus(Duration.ofHours(1)));
    }

    @Test
    @WithUser
    @DisplayName("an unread card and an answered card show on the page as the seed wrote them")
    void shouldShowTheCardsOnThePageWhenTheSeedWritesThem() {
        UUID unread = UUID.randomUUID();
        UUID answered = UUID.randomUUID();
        Instant readAt = PREPARED_AT.plus(Duration.ofHours(2));
        Instant answeredAt = PREPARED_AT.plus(Duration.ofDays(1));

        service()
                .write(
                        workspace.getId(),
                        List.of(
                                card(unread, scoping, scopingProblem, null, null),
                                card(
                                        answered,
                                        testing,
                                        testingProblem,
                                        readAt,
                                        new DevInAppFeedbackService.Response(
                                                UUID.randomUUID(),
                                                answeredAt,
                                                FeedbackUsefulness.HELPFUL,
                                                FeedbackResolution.ADDRESSED,
                                                null))));

        Feedback first = feedbackRepository.findById(unread).orElseThrow();
        assertThat(first.getPosition()).isEqualTo(7000);
        assertThat(first.getDeliveryState()).isEqualTo(FeedbackDeliveryState.PREPARED);
        assertThat(first.getThreadKey())
                .isEqualTo(FeedbackThreadKey.forPractice(
                        "scope-one-reviewable-change", developer.getId(), FeedbackChannel.IN_APP));
        Feedback second = feedbackRepository.findById(answered).orElseThrow();
        assertThat(second.getPosition()).isEqualTo(7001);
        assertThat(second.getDeliveryState()).isEqualTo(FeedbackDeliveryState.DELIVERED);

        readInAppPage(workspace)
                .jsonPath("$[?(@.id == '%s')].headline".formatted(unread))
                .isEqualTo(List.of("Headline " + unread))
                .jsonPath("$[?(@.id == '%s')].closedBy".formatted(answered))
                .isEqualTo(List.of("DEVELOPER"))
                .jsonPath("$[?(@.id == '%s')].closedAt".formatted(answered))
                .isEqualTo(List.of(answeredAt.toString()))
                .jsonPath("$[?(@.id == '%s')].evidence[0].outcome".formatted(answered))
                .isEqualTo(List.of("NOT_MET"));
    }

    @Test
    @DisplayName("a card citing another developer's observation is refused and nothing is written")
    void shouldRefuseWhenTheEvidenceIsAboutAnotherDeveloper() {
        UUID theirs =
                observe(scoping, run, 10L, teammate, NOT_MET, Severity.MINOR, PREPARED_AT.minus(Duration.ofHours(1)));
        UUID written = UUID.randomUUID();
        UUID refused = UUID.randomUUID();

        assertThatThrownBy(() -> service()
                        .write(
                                workspace.getId(),
                                List.of(
                                        card(written, scoping, scopingProblem, null, null),
                                        card(refused, scoping, theirs, null, null))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("about other work");
        assertThat(feedbackRepository.existsById(written)).isFalse();
        assertThat(feedbackRepository.existsById(refused)).isFalse();
    }

    @Test
    @DisplayName("the seed endpoints and their service do not exist while dev seeding is off, as it is by default")
    void shouldRegisterNoSeedBeanWhenSeedingIsOff() {
        assertThat(context.getBeanNamesForType(DevInAppFeedbackController.class))
                .isEmpty();
        assertThat(context.getBeanNamesForType(DevInAppFeedbackService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(DevPracticeRevisionController.class))
                .isEmpty();
    }

    /** Built by hand: the bean exists only while dev seeding is enabled, which the shared context is not. */
    private DevInAppFeedbackService service() {
        return new DevInAppFeedbackService(
                feedbackRepository, feedbackObservationRepository, observationRepository, reactionRepository);
    }

    private DevInAppFeedbackService.Card card(
            UUID id,
            Practice practice,
            UUID evidence,
            @Nullable Instant deliveredAt,
            DevInAppFeedbackService.@Nullable Response response) {
        return new DevInAppFeedbackService.Card(
                id,
                run.getId(),
                developer.getId(),
                practice.getSlug(),
                "Headline " + id,
                "What the work showed.",
                "What to try next.",
                PREPARED_AT,
                deliveredAt,
                List.of(evidence),
                response);
    }
}
