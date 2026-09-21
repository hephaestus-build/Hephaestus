package de.tum.cit.aet.hephaestus.integration.scm.github.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;

class GitHubHeadCheckProcessorTest extends BaseUnitTest {

    private static final String HEAD = "67700adac0c0f02df77fdf1bf037273e7b056aa9";

    @Mock
    private PullRequestRepository pullRequestRepository;

    @ParameterizedTest
    @CsvSource({
        "completed, success, SUCCESS",
        "completed, failure, FAILURE",
        "completed, timed_out, FAILURE",
        "completed, action_required, FAILURE",
        "completed, startup_failure, FAILURE",
        "completed, cancelled, CANCELLED",
        "in_progress, , PENDING",
        "queued, , PENDING",
    })
    void shouldMapACheckSuiteToOneState(String status, String conclusion, CheckState expected) {
        assertThat(GitHubHeadCheckProcessor.fromCheckSuite(status, conclusion)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"neutral", "skipped", "stale"})
    void shouldSayNothingForASuiteThatSaidNothingAboutTheHead(String conclusion) {
        assertThat(GitHubHeadCheckProcessor.fromCheckSuite("completed", conclusion))
                .isNull();
    }

    @ParameterizedTest
    @CsvSource({"success, SUCCESS", "failure, FAILURE", "error, FAILURE", "pending, PENDING"})
    void shouldMapACommitStatusToOneState(String state, CheckState expected) {
        assertThat(GitHubHeadCheckProcessor.fromStatus(state)).isEqualTo(expected);
    }

    @Test
    void shouldRecordTheObservationOnEveryPullRequestWithThatHead() {
        Repository repository = new Repository();
        repository.setId(5L);
        PullRequest open = new PullRequest();
        open.setHeadRefOid(HEAD);
        PullRequest alreadyFailed = new PullRequest();
        alreadyFailed.setHeadRefOid(HEAD);
        alreadyFailed.observeHeadChecks(HEAD, CheckState.FAILURE, false);
        when(pullRequestRepository.findAllByRepository_IdAndHeadRefOid(5L, HEAD))
                .thenReturn(List.of(open, alreadyFailed));

        new GitHubHeadCheckProcessor(pullRequestRepository)
                .observe(HEAD, CheckState.SUCCESS, ProcessingContext.forSync(1L, repository));

        assertThat(open.getHeadCheckState()).isEqualTo(CheckState.SUCCESS);
        assertThat(open.getHeadCheckSha()).isEqualTo(HEAD);
        verify(pullRequestRepository).save(open);
        // A suite's success does not undo another suite's failure on the same head.
        assertThat(alreadyFailed.getHeadCheckState()).isEqualTo(CheckState.FAILURE);
        verify(pullRequestRepository, never()).save(alreadyFailed);
    }
}
