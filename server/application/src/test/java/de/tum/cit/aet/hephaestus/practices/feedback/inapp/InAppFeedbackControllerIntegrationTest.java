package de.tum.cit.aet.hephaestus.practices.feedback.inapp;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackUsefulness;
import de.tum.cit.aet.hephaestus.practices.feedback.InAppFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.model.ObservationKind;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.Reaction;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The developer's own practice pages, end to end: who may read it, and what reading it records.
 */
class InAppFeedbackControllerIntegrationTest extends AbstractPracticeReviewIntegrationTest {

    private Workspace workspace;
    private Practice practice;
    private AgentJob job;
    private User developer;
    private User teammate;

    @BeforeEach
    void setUpWorkspace() {
        User owner = persistUser("in-app-owner");
        workspace = createWorkspace("in-app-ws", "In-app WS", "in-app-org", AccountType.ORG, owner);
        developer = persistUser("testuser"); // matches @WithUser
        teammate = persistUser("teammate");
        ensureWorkspaceMembership(workspace, developer, WorkspaceMembership.WorkspaceRole.MEMBER);
        ensureWorkspaceMembership(workspace, teammate, WorkspaceMembership.WorkspaceRole.MEMBER);

        practice = persistPractice(workspace, null, "ships-tests-with-changes", "Ships Tests With Changes", null);
        job = persistPullRequestReview(workspace, 101, null);
    }

    @Test
    @WithUser
    @DisplayName("feedback prepared for this developer is returned, split into headline, body and next step")
    void shouldReturnTheDevelopersOwnFeedbackWhenItIsPreparedForThem() {
        Feedback feedback = persistInAppCard(
                job,
                developer,
                7000,
                FeedbackDeliveryState.PREPARED,
                "You keep shipping untested changes",
                "Across your last three pull requests the tests did not move with the code.");
        bind(feedback, persistObservation(practice, job, developer, 101L));

        readInAppPage(workspace)
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].headline")
                .isEqualTo("You keep shipping untested changes")
                // The stored layout is split here, once: the reader never parses the body.
                .jsonPath("$[0].body")
                .isEqualTo("Across your last three pull requests the tests did not move with the code.")
                .jsonPath("$[0].nextStep")
                .isEqualTo("Write the test first next time.")
                .jsonPath("$[0].closedAt")
                .doesNotExist()
                .jsonPath("$[0].practiceSlug")
                .isEqualTo("ships-tests-with-changes")
                // The evidence names the work the way its provider does and says what the review made of it.
                .jsonPath("$[0].evidence[0].reviewedWork.kind")
                .isEqualTo("scm.pull_request")
                .jsonPath("$[0].evidence[0].reviewedWork.id")
                .isEqualTo("101")
                .jsonPath("$[0].evidence[0].reviewedWork.label")
                .isEqualTo("#101")
                .jsonPath("$[0].evidence[0].reviewedWork.title")
                .isEqualTo("Pull request 101")
                .jsonPath("$[0].evidence[0].reviewedWork.url")
                .isEqualTo("https://github.com/acme/api/pull/101")
                .jsonPath("$[0].evidence[0].reviewedWork.repositoryName")
                .isEqualTo("acme/api")
                .jsonPath("$[0].evidence[0].outcome")
                .isEqualTo("OMISSION_GAP")
                .jsonPath("$[0].evidence[0].observedAt")
                .exists();
    }

    @Test
    @WithUser
    @DisplayName("feedback prepared for somebody else is not on this developer's page")
    void shouldNotReturnFeedbackWhenItIsPreparedForSomebodyElse() {
        Feedback theirs = persistInAppCard(
                job, teammate, 7000, FeedbackDeliveryState.PREPARED, "Their habit", "Not about the caller.");
        bind(theirs, persistObservation(practice, job, teammate, 202L));

        readInAppPage(workspace).jsonPath("$.length()").isEqualTo(0);

        assertThat(feedbackRepository.findById(theirs.getId()))
                .get()
                .extracting(Feedback::getDeliveryState)
                .isEqualTo(FeedbackDeliveryState.PREPARED);
    }

    /**
     * The read is the delivery on this lane, and the flip is a compare-and-set: the second read must not
     * restamp {@code deliveredAt}, or "when they first saw it" would drift forward on every page load.
     */
    @Test
    @WithUser
    @DisplayName("the first read delivers the feedback; a second read does not re-deliver it")
    void shouldKeepTheFirstDeliveryTimeWhenTheCardIsReadAgain() {
        Feedback feedback = persistInAppCard(
                job,
                developer,
                7000,
                FeedbackDeliveryState.PREPARED,
                "You keep shipping untested changes",
                "Across your last three pull requests the tests did not move with the code.");
        bind(feedback, persistObservation(practice, job, developer, 101L));

        readInAppPage(workspace).jsonPath("$[0].readAt").doesNotExist();

        Feedback afterFirstRead = feedbackRepository.findById(feedback.getId()).orElseThrow();
        assertThat(afterFirstRead.getDeliveryState()).isEqualTo(FeedbackDeliveryState.DELIVERED);
        assertThat(afterFirstRead.getDeliveredAt()).isNotNull();

        readInAppPage(workspace).jsonPath("$[0].readAt").exists();

        assertThat(feedbackRepository.findById(feedback.getId()))
                .get()
                .extracting(Feedback::getDeliveredAt)
                .isEqualTo(afterFirstRead.getDeliveredAt());
    }

    @Test
    @DisplayName("an instance administrator viewing as the developer reads their feedback without delivering it")
    void administratorViewDoesNotDeliverTheViewedDevelopersFeedback() {
        Feedback unit = persistInAppCard(
                job, developer, 7000, FeedbackDeliveryState.PREPARED, "A practice headline", "A practice body");
        bind(unit, persistObservation(practice, job, developer, 101L));

        readAsUserView(IN_APP, workspace, developer).jsonPath("$.length()").isEqualTo(1);

        assertThat(feedbackRepository.findById(unit.getId()))
                .get()
                .extracting(Feedback::getDeliveryState)
                .isEqualTo(FeedbackDeliveryState.PREPARED);
    }

    /**
     * The card carries the developer's own answer so the page is one request, and it is the answer that
     * stands: a response they later deleted is no response. The prepared cards are delivered by the same read.
     */
    @Test
    @WithUser
    @DisplayName("a card carries the developer's current response; a card they have not answered carries none")
    void shouldCarryTheResponseThatStandsWhenTheDeveloperAnsweredTheCard() {
        Feedback answered = persistInAppCard(
                job,
                developer,
                7000,
                FeedbackDeliveryState.DELIVERED,
                "You keep shipping untested changes",
                "Across your last three pull requests the tests did not move with the code.");
        bind(answered, persistObservation(practice, job, developer, 101L));
        Practice other = persistPractice(workspace, null, "small-pull-requests", "Small Pull Requests", null);
        Feedback unanswered = persistInAppCard(
                job, developer, 7001, FeedbackDeliveryState.PREPARED, "Pull requests grow", "They do.");
        bind(unanswered, persistObservation(other, job, developer, 102L));
        Feedback withdrawn =
                persistInAppCard(job, developer, 7002, FeedbackDeliveryState.PREPARED, "Commits say what", "Not why.");
        Practice third = persistPractice(workspace, null, "commit-messages", "Commit Messages", null);
        bind(withdrawn, persistObservation(third, job, developer, 103L));
        Instant earlier = NOW.minusSeconds(120);
        reactionRepository.save(Reaction.builder()
                .feedback(answered)
                .reactorUserId(developer.getId())
                .resolution(FeedbackResolution.DISPUTED)
                .explanation("The tests were in the next commit.")
                .createdAt(earlier)
                .build());
        reactionRepository.save(Reaction.builder()
                .feedback(answered)
                .reactorUserId(developer.getId())
                .usefulness(FeedbackUsefulness.HELPFUL)
                .resolution(FeedbackResolution.ADDRESSED)
                .createdAt(earlier.plusSeconds(60))
                .build());
        respond(withdrawn, developer, FeedbackResolution.ADDRESSED, earlier);
        // A snapshot that says nothing is a deleted response, which is how the response endpoint reads it too.
        respond(withdrawn, developer, null, earlier.plusSeconds(60));

        String answeredPath = "$[?(@.id == '" + answered.getId() + "')]";
        String unansweredPath = "$[?(@.id == '" + unanswered.getId() + "')]";
        String withdrawnPath = "$[?(@.id == '" + withdrawn.getId() + "')]";
        readInAppPage(workspace)
                .jsonPath("$.length()")
                .isEqualTo(3)
                .jsonPath(answeredPath + ".response.feedbackId")
                .isEqualTo(answered.getId().toString())
                .jsonPath(answeredPath + ".response.usefulness")
                .isEqualTo("HELPFUL")
                .jsonPath(answeredPath + ".response.resolution")
                .isEqualTo("ADDRESSED")
                .jsonPath(answeredPath + ".response.comment")
                .doesNotExist()
                .jsonPath(answeredPath + ".response.respondedAt")
                .isEqualTo(earlier.plusSeconds(60).toString())
                // The answer that resolves carries the date it resolved on; an answer nobody gave does not.
                .jsonPath(answeredPath + ".closedBy")
                .isEqualTo("DEVELOPER")
                .jsonPath(answeredPath + ".closedAt")
                .isEqualTo(earlier.plusSeconds(60).toString())
                .jsonPath(unansweredPath + ".response")
                .doesNotExist()
                .jsonPath(unansweredPath + ".closedAt")
                .doesNotExist()
                .jsonPath(withdrawnPath + ".response")
                .doesNotExist()
                .jsonPath(withdrawnPath + ".closedAt")
                .doesNotExist();

        assertThat(feedbackRepository.findAllById(List.of(unanswered.getId(), withdrawn.getId())))
                .extracting(Feedback::getDeliveryState)
                .containsOnly(FeedbackDeliveryState.DELIVERED);
    }

    /**
     * Composition freezes text; it must not freeze the rules. Feedback whose evidence was measured under
     * review rules the practice has since changed stays on the page, closed, and says when the rules moved: a
     * card that vanished without a word would read as a claim withdrawn. Every edit to a practice appends a
     * revision, so the date is the rule change, never a later edit that touched only the prose.
     */
    @Test
    @WithUser
    @DisplayName("feedback whose practice rules changed stays, closed, dated by the rule change")
    void shouldCloseFeedbackAtTheRuleChangeWhenItsPracticeChanged() {
        Feedback feedback = persistInAppCard(
                job,
                developer,
                7000,
                FeedbackDeliveryState.PREPARED,
                "You keep shipping untested changes",
                "Across your last three pull requests the tests did not move with the code.");
        bind(feedback, persistObservation(practice, job, developer, 101L));

        // The measurement stays pinned to revision 1; revision 2 changes the rules, so its claim is stale.
        Instant beforeTheRuleChange = Instant.now();
        practice.setCriteria("A rewritten rubric, measuring something else");
        practice = practiceRepository.saveAndFlush(practice);
        practice.setCurrentRevision(practiceRevisionRepository.save(new PracticeRevision(practice, 2)));
        practice = practiceRepository.saveAndFlush(practice);

        // Revision 3 reworks the prose only and carries the same review rules as revision 2.
        practice.setWhyItMatters("Because a change nobody tested is a change nobody can trust.");
        practice = practiceRepository.saveAndFlush(practice);
        PracticeRevision third = practiceRevisionRepository.save(new PracticeRevision(practice, 3));
        practice.setCurrentRevision(third);
        practice = practiceRepository.saveAndFlush(practice);
        Instant theProseEdit = Objects.requireNonNull(third.getCreatedAt());

        readInAppPage(workspace)
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].id")
                .isEqualTo(feedback.getId().toString())
                .jsonPath("$[0].closedBy")
                .isEqualTo("PRACTICE_CHANGED")
                .jsonPath("$[0].closedAt")
                .value(at -> assertThat(Instant.parse((String) at))
                        .isAfterOrEqualTo(beforeTheRuleChange.truncatedTo(ChronoUnit.MILLIS))
                        .isBefore(theProseEdit));
    }

    /**
     * Composition freezes text; it must not freeze permission. Feedback whose evidence source may not be cited
     * to the developer is not shown, and the ledger row stays, because hiding is not deleting.
     */
    @Test
    @WithUser
    @DisplayName("feedback whose evidence may not be cited is hidden at read time, not deleted")
    void shouldHideFeedbackWithoutDeletingItWhenItsEvidenceMayNotBeCited() {
        Feedback feedback = persistInAppCard(
                job,
                developer,
                7000,
                FeedbackDeliveryState.PREPARED,
                "You keep shipping untested changes",
                "Across your last three pull requests the tests did not move with the code.");
        // A source the catalog holds no use decision for: nothing permits citing it to the developer.
        bind(
                feedback,
                observe(
                        practice,
                        job,
                        101L,
                        developer,
                        ObservationKind.OMISSION_GAP,
                        Severity.MAJOR,
                        Instant.now(),
                        "{\"citations\":[{\"sourceKind\":\"scm.pull-request.withdrawn\",\"quote\":\"example\"}]}"));

        readInAppPage(workspace).jsonPath("$.length()").isEqualTo(0);

        assertThat(feedbackRepository.findById(feedback.getId()))
                .get()
                .extracting(Feedback::getDeliveryState)
                .isEqualTo(FeedbackDeliveryState.PREPARED);
    }

    private UUID persistObservation(Practice about, AgentJob agentJob, User subject, long artifactId) {
        return observe(
                about, agentJob, artifactId, subject, ObservationKind.OMISSION_GAP, Severity.MAJOR, Instant.now());
    }

    private Feedback persistInAppCard(
            AgentJob agentJob,
            User recipient,
            int position,
            FeedbackDeliveryState state,
            String headline,
            String message) {
        return persistInAppFeedback(
                agentJob,
                recipient,
                position,
                state,
                InAppFeedbackBody.render(headline, message, "Write the test first next time."),
                Instant.now());
    }
}
