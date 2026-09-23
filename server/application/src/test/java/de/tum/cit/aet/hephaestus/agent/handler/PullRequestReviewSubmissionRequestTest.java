package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PullRequestReviewSubmissionRequestTest extends BaseUnitTest {

    private ScmEventPayload.PullRequestData samplePullRequestData() {
        return new ScmEventPayload.PullRequestData(
                456L,
                42,
                "Fix bug",
                "Body",
                Issue.State.OPEN,
                false,
                false,
                10,
                5,
                3,
                "https://github.com/owner/repo/pull/42",
                new RepositoryRef(123L, "owner/repo", "main"),
                789L,
                Instant.now(),
                Instant.now(),
                null,
                null,
                null);
    }

    @Nested
    class Construction {

        @Test
        void shouldAcceptValidInput() {
            var request = new PullRequestReviewSubmissionRequest(
                    samplePullRequestData(), "feature/x", "abc123", "main", "base");

            assertThat(request.pullRequest()).isNotNull();
            assertThat(request.headRefName()).isEqualTo("feature/x");
            assertThat(request.headRefOid()).isEqualTo("abc123");
            assertThat(request.baseRefName()).isEqualTo("main");
        }

        @Test
        void shouldRejectBlankHeadRefName() {
            assertThatThrownBy(() -> new PullRequestReviewSubmissionRequest(
                            samplePullRequestData(), "  ", "sha", "main", "base"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("headRefName");
        }

        @Test
        void shouldRejectBlankHeadRefOid() {
            assertThatThrownBy(() -> new PullRequestReviewSubmissionRequest(
                            samplePullRequestData(), "branch", " ", "main", "base"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("headRefOid");
        }

        @Test
        void shouldRejectBlankBaseRefName() {
            assertThatThrownBy(() -> new PullRequestReviewSubmissionRequest(
                            samplePullRequestData(), "branch", "sha", "  ", "base"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("baseRefName");
        }
    }

    @Test
    void shouldRejectBlankPinnedBaseButAcceptAnAbsentOne() {
        assertThatThrownBy(() ->
                        new PullRequestReviewSubmissionRequest(samplePullRequestData(), "branch", "head", "main", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseRefOid");
        // A GitLab webhook pins no base; preparation resolves the target branch instead.
        assertThat(new PullRequestReviewSubmissionRequest(samplePullRequestData(), "branch", "head", "main", null)
                        .baseRefOid())
                .isNull();
    }

    @Nested
    class ObservationPopulation {

        @Test
        void aLifecycleEventPutsTheRunInTheUnbiasedPopulation() {
            var request = new PullRequestReviewSubmissionRequest(
                    samplePullRequestData(), "branch", "sha", "main", "base", ScmSignals.PULL_REQUEST_READY);

            assertThat(request.observationOrigin()).isEqualTo(ObservationOrigin.LIVE);
        }

        @Test
        void aRunNobodysEventOccasionedIsSelfSelected() {
            // The bot command and the dev trigger both submit without a trigger event: a person asked.
            // Reviews people ask for are drawn from work they were already unsure of, so folding them into
            // the event-driven series would read that selection as a change in behaviour.
            var request =
                    new PullRequestReviewSubmissionRequest(samplePullRequestData(), "branch", "sha", "main", "base");

            assertThat(request.observationOrigin()).isEqualTo(ObservationOrigin.MANUAL);
        }
    }
}
