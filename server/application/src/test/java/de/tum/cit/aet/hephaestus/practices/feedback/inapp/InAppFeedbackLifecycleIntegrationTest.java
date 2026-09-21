package de.tum.cit.aet.hephaestus.practices.feedback.inapp;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.handler.FeedbackLedgerRecorder;
import de.tum.cit.aet.hephaestus.agent.handler.inapp.ComposedInAppMessage;
import de.tum.cit.aet.hephaestus.agent.handler.inapp.InAppFeedbackPreparer;
import de.tum.cit.aet.hephaestus.agent.handler.inapp.InAppRoutingDecision;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.feedback.InAppFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.feedback.PreviousInAppFeedback;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.Reaction;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * What becomes of a card on the developer's page once it is no longer the newest word on its habit: a
 * newer card about the practice replaces it while it is open, a closed card stays as the record beside the
 * newer one, and a closed card leaves the page thirty days after it closed. Every history here is written by
 * the test that reads it.
 */
class InAppFeedbackLifecycleIntegrationTest extends AbstractPracticeReviewIntegrationTest {

    private static final String IN_APP = "/workspaces/{slug}/practices/feedback/in-app";

    /** Whole seconds, so what Postgres stores is what the JSON says. */
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private static final Instant FIRST_PREPARED_AT = NOW.minus(Duration.ofDays(20));

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private PreviousInAppFeedback previousInAppFeedback;

    @Autowired
    private InAppFeedbackPreparer preparer;

    private Workspace workspace;
    private Practice practice;
    private User developer;

    @BeforeEach
    void seedWorkspace() {
        User owner = persistUser("lifecycle-owner");
        workspace = createWorkspace("lifecycle-ws", "Lifecycle WS", "lifecycle-org", AccountType.ORG, owner);
        developer = persistUser("testuser"); // matches @WithUser
        ensureWorkspaceMembership(workspace, developer, WorkspaceMembership.WorkspaceRole.MEMBER);
        practice = persistPractice(workspace, null, "reviewable-diff-size", "Keep the diff reviewable", null);
    }

    /**
     * The second review, after the cooldown, finds the habit again on two newer pieces of work. The card
     * the developer already read is retired for the newer one: the page is a list of habits to work on, and
     * one habit gets one open card.
     */
    @Test
    @WithUser
    @DisplayName("a newer card about the practice supersedes the open one, and the page shows one open card")
    void shouldSupersedeTheOpenCardWhenANewCardIsPrepared() {
        Feedback first = firstCard(FIRST_PREPARED_AT, FeedbackDeliveryState.DELIVERED);

        Feedback second = secondReview(first);

        assertThat(state(first)).isEqualTo(FeedbackDeliveryState.SUPERSEDED);
        assertThat(second.getReplacesId()).isEqualTo(first.getId());
        page().jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].id")
                .isEqualTo(second.getId().toString())
                .jsonPath("$[0].evidence[*].work.label")
                .isEqualTo(List.of("#12", "#11"))
                .jsonPath("$[0].resolvedByWorkAt")
                .doesNotExist();
    }

    @Test
    @WithUser
    @DisplayName("a card resolved by the developer is not superseded; it stays as the record beside the new one")
    void shouldKeepAResolvedCardBesideTheNewOne() {
        Feedback first = firstCard(FIRST_PREPARED_AT, FeedbackDeliveryState.DELIVERED);
        markAddressed(first, developer, FIRST_PREPARED_AT.plus(Duration.ofDays(2)));

        Feedback second = secondReview(first);

        assertThat(state(first)).isEqualTo(FeedbackDeliveryState.DELIVERED);
        assertThat(second.getReplacesId()).isNull();
        page().jsonPath("$.length()")
                .isEqualTo(2)
                .jsonPath("$[0].id")
                .isEqualTo(second.getId().toString())
                .jsonPath("$[1].id")
                .isEqualTo(first.getId().toString())
                .jsonPath("$[1].response.resolution")
                .isEqualTo("ADDRESSED");
    }

    @Test
    @WithUser
    @DisplayName("a card resolved by the developer leaves the page thirty days after the response")
    void shouldDropACardThirtyDaysAfterTheDeveloperResolvedIt() {
        Instant preparedAt = NOW.minus(Duration.ofDays(40));
        Feedback gone = firstCard(preparedAt, FeedbackDeliveryState.DELIVERED);
        markAddressed(gone, developer, NOW.minus(Duration.ofDays(31)));
        Feedback stillThere = card(preparedAt.plus(Duration.ofHours(1)), FeedbackDeliveryState.DELIVERED, 20);
        respond(stillThere, FeedbackResolution.NOT_APPLICABLE, NOW.minus(Duration.ofDays(29)));

        page().jsonPath("$[*].id")
                .isEqualTo(List.of(stillThere.getId().toString()))
                .jsonPath("$[0].response.resolution")
                .isEqualTo("NOT_APPLICABLE");
    }

    @Test
    @WithUser
    @DisplayName("a card resolved by the work leaves the page thirty days after the third clean piece of work")
    void shouldDropACardThirtyDaysAfterTheWorkResolvedIt() {
        Instant preparedAt = NOW.minus(Duration.ofDays(40));
        Feedback gone = firstCard(preparedAt, FeedbackDeliveryState.DELIVERED);
        cleanReview(11, NOW.minus(Duration.ofDays(38)));
        cleanReview(12, NOW.minus(Duration.ofDays(36)));
        cleanReview(13, NOW.minus(Duration.ofDays(31)));

        page().jsonPath("$.length()").isEqualTo(0);

        assertThat(state(gone)).isEqualTo(FeedbackDeliveryState.DELIVERED);
    }

    @Test
    @WithUser
    @DisplayName("a card the work resolved less than thirty days ago is still on the page, resolved")
    void shouldKeepACardTheWorkResolvedRecently() {
        Instant preparedAt = NOW.minus(Duration.ofDays(40));
        Feedback resolved = firstCard(preparedAt, FeedbackDeliveryState.DELIVERED);
        cleanReview(11, NOW.minus(Duration.ofDays(38)));
        cleanReview(12, NOW.minus(Duration.ofDays(36)));
        cleanReview(13, NOW.minus(Duration.ofDays(29)));

        page().jsonPath("$[*].id")
                .isEqualTo(List.of(resolved.getId().toString()))
                .jsonPath("$[0].resolvedByWorkAt")
                .isEqualTo(NOW.minus(Duration.ofDays(29)).toString());
    }

    /** The first card about the practice, written from a slip on #10 an hour before it was prepared. */
    private Feedback firstCard(Instant preparedAt, FeedbackDeliveryState state) {
        return card(preparedAt, state, 10);
    }

    private Feedback card(Instant preparedAt, FeedbackDeliveryState state, int number) {
        AgentJob run = persistPullRequestReview(workspace, number, preparedAt.minus(Duration.ofHours(1)));
        UUID problem = observe(
                practice, run, number, developer, "ABSENT", "BAD", "MAJOR", preparedAt.minus(Duration.ofHours(1)));
        Feedback feedback = persistInAppFeedback(
                run,
                developer,
                FeedbackLedgerRecorder.IN_APP_UNIT_ORDINAL_BASE,
                state,
                InAppFeedbackBody.render(
                        "Your diffs keep growing",
                        "Two pull requests in a row went past the size that reviews well.",
                        "Split the next one."),
                preparedAt);
        bind(feedback, problem);
        return feedback;
    }

    /**
     * The second review, after the cooldown: the habit on #11 and #12, composed into a card the way the
     * lane writes one — the previous card read as the page reads it, and the message written beside or
     * over it accordingly.
     */
    private Feedback secondReview(Feedback first) {
        AgentJob run = persistPullRequestReview(workspace, 12, NOW);
        List<Observation> evidence = List.of(
                observation(observe(
                        practice, run, 12, developer, "ABSENT", "BAD", "MAJOR", NOW.minus(Duration.ofHours(1)))),
                observation(observe(
                        practice,
                        persistPullRequestReview(workspace, 11, NOW.minus(Duration.ofDays(3))),
                        11,
                        developer,
                        "ABSENT",
                        "BAD",
                        "MAJOR",
                        NOW.minus(Duration.ofDays(3)))));
        PreviousInAppFeedback.Previous previous = previousInAppFeedback
                .find(workspace.getId(), developer.getId(), practice.getSlug())
                .orElseThrow();
        assertThat(previous.id()).isEqualTo(first.getId());
        int prepared = preparer.prepare(
                run.getId(),
                workspace.getId(),
                developer.getId(),
                List.of(new InAppFeedbackPreparer.RoutedMessage(
                        new ComposedInAppMessage(
                                practice.getSlug(),
                                "Your diffs are still growing",
                                "Two more pull requests went past the size that reviews well.",
                                "Split the next one."),
                        InAppRoutingDecision.ADMIT,
                        evidence,
                        previous.isOpen() ? previous.id() : null)),
                FeedbackLedgerRecorder.IN_APP_UNIT_ORDINAL_BASE);
        assertThat(prepared).isEqualTo(1);
        return feedbackRepository.findAll().stream()
                .filter(feedback -> run.getId().equals(feedback.getAgentJobId()))
                .findFirst()
                .orElseThrow();
    }

    private Observation observation(UUID id) {
        return observationRepository.findById(id).orElseThrow();
    }

    /** A review of one of the developer's pull requests on which the practice raised nothing. */
    private void cleanReview(int number, Instant reviewedAt) {
        AgentJob run = persistPullRequestReview(workspace, number, reviewedAt);
        observe(practice, run, number, developer, "PRESENT", "GOOD", null, reviewedAt);
    }

    private void respond(Feedback feedback, FeedbackResolution resolution, Instant respondedAt) {
        reactionRepository.save(Reaction.builder()
                .feedback(feedback)
                .reactorUserId(developer.getId())
                .resolution(resolution)
                .createdAt(respondedAt)
                .build());
    }

    private FeedbackDeliveryState state(Feedback feedback) {
        return feedbackRepository
                .findByIdAndWorkspaceId(feedback.getId(), workspace.getId())
                .orElseThrow()
                .getDeliveryState();
    }

    private WebTestClient.BodyContentSpec page() {
        return webTestClient
                .get()
                .uri(IN_APP, workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }
}
