package de.tum.cit.aet.hephaestus.profile;

import static org.assertj.core.api.Assertions.assertThatNoException;

import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Guards the nullable date-range filter against Postgres parameter-type inference. A bind
 * parameter used only in {@code :p IS NULL} has no type context, so without an explicit CAST
 * Postgres rejects the statement ("could not determine data type"). The query executes against
 * the prepared statement regardless of data, so an empty DB still exercises the failure path.
 */
@DisplayName("ProfilePullRequestQueryRepository date-range type inference")
class ProfilePullRequestQueryRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ProfilePullRequestQueryRepository repository;

    @Test
    @DisplayName("null since/until executes on Postgres")
    void nullDateBoundsExecute() {
        assertThatNoException()
                .isThrownBy(
                        () -> repository.findAuthoredByUserIdAndStates(1L, Set.of(Issue.State.OPEN), 1L, null, null));
    }

    @Test
    @DisplayName("bounded since/until executes on Postgres")
    void boundedDateBoundsExecute() {
        assertThatNoException()
                .isThrownBy(() -> repository.findAuthoredByUserIdAndStates(
                        1L,
                        Set.of(Issue.State.OPEN),
                        1L,
                        Instant.parse("2020-01-01T00:00:00Z"),
                        Instant.parse("2030-01-01T00:00:00Z")));
    }
}
