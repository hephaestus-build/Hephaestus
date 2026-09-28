package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class PullRequestReviewRequestsTest extends BaseUnitTest {

    /**
     * PostgreSQL stores microseconds: a list received earlier within the stored microsecond would otherwise count as
     * older while the stored instant is in memory, and as newer once it is read back.
     */
    @Test
    void shouldTreatListsReceivedWithinOneMicrosecondAsReceivedAtTheSameInstant() {
        PullRequest pr = pullRequest();
        pr.replaceRequestedReviewers(
                reviewers(user(1L), RequestedReviewer.ReviewState.APPROVED),
                Instant.parse("2026-01-31T18:05:00.000001900Z"));

        boolean changed = pr.replaceRequestedReviewers(
                reviewers(user(2L), RequestedReviewer.ReviewState.UNREVIEWED),
                Instant.parse("2026-01-31T18:05:00.000001100Z"));

        assertThat(changed).isTrue();
        assertThat(pr.getReviewersObservedAt()).isEqualTo(Instant.parse("2026-01-31T18:05:00.000001Z"));
    }

    private static PullRequest pullRequest() {
        PullRequest pr = new PullRequest();
        pr.setId(10L);
        return pr;
    }

    private static User user(long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private static Map<User, RequestedReviewer.@Nullable ReviewState> reviewers(
            User user, RequestedReviewer.@Nullable ReviewState state) {
        Map<User, RequestedReviewer.@Nullable ReviewState> reviewers = new HashMap<>();
        reviewers.put(user, state);
        return reviewers;
    }
}
