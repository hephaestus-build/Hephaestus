package de.tum.cit.aet.hephaestus.practices.feedback.inapp;

import static de.tum.cit.aet.hephaestus.practices.model.ObservationKind.DEMONSTRATED_STRENGTH;
import static de.tum.cit.aet.hephaestus.practices.model.ObservationKind.OMISSION_GAP;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.feedback.InAppFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.feedback.PreviousInAppFeedback;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.Reaction;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The work resolves the feedback: a card on the developer's page reports how many pieces of their work in a
 * row have come back clean on the practice since it was prepared, and once three have, when and on what it
 * was resolved. Every history here is written by the test that reads it.
 */
class InAppFeedbackWorkResolutionIntegrationTest extends AbstractPracticeReviewIntegrationTest {

    private static final Instant PREPARED_AT = NOW.minus(Duration.ofDays(10));

    @Autowired
    private PreviousInAppFeedback previousInAppFeedback;

    private Workspace workspace;
    private Practice practice;
    private User developer;
    private User teammate;
    private Feedback feedback;

    @BeforeEach
    void seedFeedback() {
        User owner = persistUser("work-resolution-owner");
        workspace = createWorkspace(
                "work-resolution-ws", "Work Resolution WS", "work-resolution-org", AccountType.ORG, owner);
        developer = persistUser("testuser"); // matches @WithUser
        teammate = persistUser("teammate");
        ensureWorkspaceMembership(workspace, developer, WorkspaceMembership.WorkspaceRole.MEMBER);
        ensureWorkspaceMembership(workspace, teammate, WorkspaceMembership.WorkspaceRole.MEMBER);
        practice = persistPractice(workspace, null, "reviewable-diff-size", "Keep the diff reviewable", null);

        // The slip the feedback was written from, reviewed shortly before the feedback was prepared.
        AgentJob run = persistPullRequestReview(workspace, 10, PREPARED_AT.minus(Duration.ofHours(1)));
        UUID problem = observe(
                practice, run, 10L, developer, OMISSION_GAP, Severity.MAJOR, PREPARED_AT.minus(Duration.ofHours(1)));
        feedback = persistInAppFeedback(
                run,
                developer,
                1,
                FeedbackDeliveryState.DELIVERED,
                InAppFeedbackBody.render(
                        "Your diffs keep growing",
                        "Three pull requests in a row went past the size that reviews well.",
                        "Split the next one."),
                PREPARED_AT);
        bind(feedback, problem);
    }

    @Test
    @WithUser
    @DisplayName("feedback with no clean work after it is not resolved")
    void shouldNotResolveWhenNoCleanWorkFollows() {
        card().jsonPath("$[0].cleanNeeded")
                .isEqualTo(3)
                .jsonPath("$[0].cleanWork.length()")
                .isEqualTo(0)
                .jsonPath("$[0].closedAt")
                .doesNotExist();
    }

    /**
     * The evidence and the clean work are named the same way: the card links "#10" as it links "#11", and
     * the outcome comes from the observation itself rather than from whatever the standing lists.
     */
    @Test
    @WithUser
    @DisplayName("the evidence names the work it was observed on and what the review made of it")
    void shouldNameEvidenceAndCleanWorkAlikeWhenTheCardListsBoth() {
        cleanReview(practice, developer, 11, daysAfterPreparation(1));

        card().jsonPath("$[0].evidence.length()")
                .isEqualTo(1)
                .jsonPath("$[0].evidence[0].reviewedWork.label")
                .isEqualTo("#10")
                .jsonPath("$[0].evidence[0].reviewedWork.kind")
                .isEqualTo("scm.pull_request")
                .jsonPath("$[0].evidence[0].reviewedWork.id")
                .isEqualTo("10")
                .jsonPath("$[0].evidence[0].reviewedWork.url")
                .isEqualTo("https://github.com/acme/api/pull/10")
                .jsonPath("$[0].evidence[0].outcome")
                .isEqualTo("OMISSION_GAP")
                .jsonPath("$[0].evidence[0].observedAt")
                .isEqualTo(PREPARED_AT.minus(Duration.ofHours(1)).toString())
                .jsonPath("$[0].cleanWork[0].reviewedWork.label")
                .isEqualTo("#11")
                .jsonPath("$[0].cleanWork[0].reviewedWork.url")
                .isEqualTo("https://github.com/acme/api/pull/11")
                .jsonPath("$[0].cleanWork[0].reviewedAt")
                .isEqualTo(daysAfterPreparation(1).toString());
    }

    @Test
    @WithUser
    @DisplayName("a problem starts the count over")
    void shouldStartTheCountOverWhenAProblemFollowsCleanWork() {
        cleanReview(practice, developer, 11, daysAfterPreparation(1));
        cleanReview(practice, developer, 12, daysAfterPreparation(2));
        AgentJob slip = persistPullRequestReview(workspace, 13, daysAfterPreparation(3));
        observe(practice, slip, 13L, developer, OMISSION_GAP, Severity.MAJOR, daysAfterPreparation(3));
        cleanReview(practice, developer, 14, daysAfterPreparation(4));

        card().jsonPath("$[0].cleanWork[*].reviewedWork.label")
                .isEqualTo(List.of("#14"))
                .jsonPath("$[0].closedAt")
                .doesNotExist();
    }

    @Test
    @WithUser
    @DisplayName(
            "three clean pieces of work in a row resolve it, dated by the third, and a later slip does not undo that")
    void shouldResolveOnTheThirdWhenThreeCleanPiecesOfWorkFollow() {
        cleanReview(practice, developer, 11, daysAfterPreparation(1));
        cleanReview(practice, developer, 12, daysAfterPreparation(2));
        cleanReview(practice, developer, 13, daysAfterPreparation(3));
        AgentJob later = persistPullRequestReview(workspace, 14, daysAfterPreparation(4));
        observe(practice, later, 14L, developer, OMISSION_GAP, Severity.MAJOR, daysAfterPreparation(4));

        card().jsonPath("$[0].closedBy")
                .isEqualTo("WORK")
                .jsonPath("$[0].closedAt")
                .isEqualTo(daysAfterPreparation(3).toString())
                .jsonPath("$[0].cleanWork[*].reviewedWork.label")
                .isEqualTo(List.of("#11", "#12", "#13"))
                .jsonPath("$[0].cleanWork[0].reviewedWork.kind")
                .isEqualTo("scm.pull_request")
                .jsonPath("$[0].cleanWork[0].reviewedWork.url")
                .isEqualTo("https://github.com/acme/api/pull/11");
    }

    @Test
    @WithUser
    @DisplayName("another developer's clean work does not count")
    void shouldNotCountWorkWhenAnotherDeveloperDidIt() {
        for (int number = 11; number <= 13; number++) {
            AgentJob run = persistPullRequestReview(workspace, number, daysAfterPreparation(number - 10));
            observe(practice, run, number, teammate, DEMONSTRATED_STRENGTH, null, daysAfterPreparation(number - 10));
        }

        card().jsonPath("$[0].cleanWork.length()")
                .isEqualTo(0)
                .jsonPath("$[0].closedAt")
                .doesNotExist();
    }

    @Test
    @WithUser
    @DisplayName("marking it addressed still resolves it, whatever the work says")
    void shouldStillResolveWhenMarkedAddressed() {
        Instant respondedAt = PREPARED_AT.plus(Duration.ofDays(1));
        markAddressed(feedback, developer, respondedAt);

        // The card itself is where the reader sees it closed; the work never resolved it. The date the
        // answer closed it is on the wire, so no reader has to decide again which answers resolve.
        card().jsonPath("$[0].response.resolution")
                .isEqualTo("ADDRESSED")
                .jsonPath("$[0].response.respondedAt")
                .isEqualTo(respondedAt.toString())
                .jsonPath("$[0].closedBy")
                .isEqualTo("DEVELOPER")
                .jsonPath("$[0].closedAt")
                .isEqualTo(respondedAt.toString())
                .jsonPath("$[0].cleanWork.length()")
                .isEqualTo(0);
    }

    /**
     * The next card about this habit starts where this one was answered: after the developer marked it
     * addressed, or after the third clean piece of work, whichever came first — and, while it is open,
     * after this card was prepared, since the next card replaces it and what it cited is not news.
     */
    @Test
    @DisplayName("the previous card's resolution is where the next card's evidence starts")
    void shouldTellTheNextCardWhereToStartWhenThePreviousOneClosed() {
        assertThat(previousInAppFeedback.find(workspace.getId(), developer.getId(), practice.getSlug(), NOW))
                .get()
                .satisfies(open -> {
                    assertThat(open.id()).isEqualTo(feedback.getId());
                    assertThat(open.isOpen()).isTrue();
                    assertThat(open.nextEvidenceSince(PREPARED_AT.minus(Duration.ofDays(1))))
                            .isEqualTo(PREPARED_AT);
                });
        assertThat(previousInAppFeedback.find(workspace.getId(), developer.getId(), "never-written-about", NOW))
                .isEmpty();

        markAddressed(feedback, developer, daysAfterPreparation(2));
        assertThat(previous()).isEqualTo(daysAfterPreparation(2));

        // The work resolves it too, later: the earlier way stands.
        cleanReview(practice, developer, 11, daysAfterPreparation(3));
        cleanReview(practice, developer, 12, daysAfterPreparation(4));
        cleanReview(practice, developer, 13, daysAfterPreparation(5));
        assertThat(previous()).isEqualTo(daysAfterPreparation(2));

        // The response that stands is what counts; a later dispute leaves the work's resolution.
        reactionRepository.save(Reaction.builder()
                .feedback(feedback)
                .reactorUserId(developer.getId())
                .resolution(FeedbackResolution.DISPUTED)
                .explanation("The diffs were as small as the change allowed.")
                .createdAt(daysAfterPreparation(6))
                .build());
        assertThat(previous()).isEqualTo(daysAfterPreparation(5));
        // And the card says the same: a dispute is an answer that leaves the feedback open.
        card().jsonPath("$[0].closedBy")
                .isEqualTo("WORK")
                .jsonPath("$[0].closedAt")
                .isEqualTo(daysAfterPreparation(5).toString());
    }

    /** When the previous card about the practice closed, which the test expects to be set. */
    private Instant previous() {
        return Objects.requireNonNull(previousInAppFeedback
                .find(workspace.getId(), developer.getId(), practice.getSlug(), NOW)
                .orElseThrow()
                .closedAt());
    }

    private WebTestClient.BodyContentSpec card() {
        return readInAppPage(workspace)
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].id")
                .isEqualTo(feedback.getId().toString());
    }

    private static Instant daysAfterPreparation(int days) {
        return PREPARED_AT.plus(Duration.ofDays(days));
    }
}
