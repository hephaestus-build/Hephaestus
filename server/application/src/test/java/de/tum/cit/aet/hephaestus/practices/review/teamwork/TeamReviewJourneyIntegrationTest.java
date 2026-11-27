package de.tum.cit.aet.hephaestus.practices.review.teamwork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.io.Serial;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The first integrated proof of a team review: submit a pinned synthetic version, run the existing practice review, read
 * the same anchored result in the app and in the editor, submit a second version that leaves the first as it was, and
 * deny everyone else. It runs once for each capture, and both must give the same practice version and the same shape of
 * observation.
 *
 * <p>The journey does not run yet. This test holds the step it stops at, so the pull request that makes a step work
 * writes that step's calls and moves {@link #FIRST_MISSING_STEP} forward. The last one removes the expectation and runs
 * the journey as an ordinary test.
 */
class TeamReviewJourneyIntegrationTest extends BaseIntegrationTest {

    enum Step {
        STAFF_CREATE_A_TEAM_PROJECT,
        TWO_PEOPLE_JOIN_IT,
        STAFF_OFFER_ONE_REVIEW_SETUP,
        A_MEMBER_SUBMITS_VERSION_1_ONCE,
        A_MEMBER_REQUESTS_ITS_REVIEW_ONCE,
        THE_REVIEW_SEALS_AN_OBSERVATION_WITH_TWO_ANCHORS,
        THE_APP_AND_THE_EDITOR_SHOW_THE_SAME_RESULT,
        A_MEMBER_SUBMITS_VERSION_2,
        VERSION_1_AND_ITS_REVIEW_ARE_UNCHANGED,
        NOBODY_OUTSIDE_THE_TEAM_SEES_ANY_OF_IT
    }

    private static final Step FIRST_MISSING_STEP = Step.STAFF_CREATE_A_TEAM_PROJECT;

    /** Everything about a sealed observation that must not depend on the tool its version came from. */
    private record Shape(long practiceVersion, String presentation, String outcome, String summary, String nextStep) {}

    private static final class MissingStep extends AssertionError {
        @Serial
        private static final long serialVersionUID = 1L;

        private final Step step;

        private MissingStep(Step step, AssertionError cause) {
            super("The journey stops at " + step, cause);
            this.step = step;
        }
    }

    @Autowired
    private ReviewIntegrationData data;

    @Autowired
    private WebTestClient http;

    private ReviewIntegrationData.Scenario course;
    private TeamReviewDriver product;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        course = data.scenario();
        product = new HttpTeamReviewDriver(
                http, course.workspace().getWorkspaceSlug(), ReviewIntegrationData.memberToken(course));
    }

    @Test
    void shouldStillStopAtItsFirstMissingStep() {
        // The staff token reaches this workspace, so a failing step is a missing capability, not a broken fixture.
        http.get()
                .uri("/workspaces/" + course.workspace().getWorkspaceSlug() + "/practices/reviews")
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(course)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);

        assertThatThrownBy(() -> {
                    var board = journey(Captures.board());
                    var model = journey(Captures.model());
                    assertThat(model).isEqualTo(board);
                })
                .isInstanceOfSatisfying(MissingStep.class, missing -> assertThat(missing.step)
                        .isEqualTo(FIRST_MISSING_STEP));
    }

    private Shape journey(Captures.Capture capture) {
        long practice = Objects.requireNonNull(course.practice().getId());
        var team = step(
                Step.STAFF_CREATE_A_TEAM_PROJECT, () -> product.createTeamProject("Cup return " + capture.name()));
        var members = step(Step.TWO_PEOPLE_JOIN_IT, () -> product.twoMembers(team));
        var first = members.getFirst();
        var second = members.getLast();
        var setup = step(Step.STAFF_OFFER_ONE_REVIEW_SETUP, () -> product.offer(team, practice));

        var version1 = step(Step.A_MEMBER_SUBMITS_VERSION_1_ONCE, () -> {
            var key = UUID.randomUUID();
            var submitted = product.submit(first, setup, capture, capture.version1(), key);
            assertThat(product.submit(first, setup, capture, capture.version1(), key))
                    .isEqualTo(submitted);
            return submitted;
        });
        var review = step(Step.A_MEMBER_REQUESTS_ITS_REVIEW_ONCE, () -> {
            var requested = product.requestReview(first, version1);
            assertThat(product.requestReview(second, version1)).isEqualTo(requested);
            return requested;
        });
        var sealed = step(Step.THE_REVIEW_SEALS_AN_OBSERVATION_WITH_TWO_ANCHORS, () -> {
            product.runReview(review, capture.cited());
            var read = product.readInApp(first, review);
            assertThat(read.presentation()).isEqualTo("OBSERVATIONS");
            assertThat(read.anchors())
                    .containsExactlyElementsOf(capture.cited().stream()
                            .map(c -> new TeamReviewDriver.Anchor(version1, c.elementId(), c.quote()))
                            .toList());
            assertThat(product.composerRuns(review)).isZero();
            return read;
        });
        step(Step.THE_APP_AND_THE_EDITOR_SHOW_THE_SAME_RESULT, () -> {
            assertThat(product.readInEditor(second, review)).isEqualTo(sealed);
            return sealed;
        });
        var version2 = step(
                Step.A_MEMBER_SUBMITS_VERSION_2,
                () -> product.submit(second, setup, capture, capture.version2(), UUID.randomUUID()));
        // Version 2 renames or deletes an element that the observation on version 1 cites.
        step(Step.VERSION_1_AND_ITS_REVIEW_ARE_UNCHANGED, () -> {
            assertThat(version2).isNotEqualTo(version1);
            assertThat(product.readInApp(first, review)).isEqualTo(sealed);
            return sealed;
        });
        step(Step.NOBODY_OUTSIDE_THE_TEAM_SEES_ANY_OF_IT, () -> {
            assertThat(product.statusForAnOutsider(review)).isEqualTo(404);
            assertThat(product.staffList()).doesNotContain(review);
            product.leave(team, second);
            assertThat(product.statusFor(second, review)).isEqualTo(404);
            return sealed;
        });
        return new Shape(
                sealed.practiceVersion(),
                sealed.presentation(),
                sealed.outcome(),
                sealed.summary(),
                sealed.nextStep());
    }

    private static <T> T step(Step step, Supplier<T> action) {
        try {
            return action.get();
        } catch (AssertionError failure) {
            throw new MissingStep(step, failure);
        }
    }
}
