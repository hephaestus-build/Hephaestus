package de.tum.cit.aet.hephaestus.integration.scm.github.check;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.HeadCheckRuns;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.HeadCheckRunsRecorder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GitHubHeadCheckRunsRereadTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");
    private static final String REPOSITORY = "acme/web";
    private static final HeadCheckRuns.Run RUNNING =
            new HeadCheckRuns.Run("build", "GitHub Actions", "in_progress", false, null, null, null);
    private static final HeadCheckRuns.Run PASSED =
            new HeadCheckRuns.Run("build", "GitHub Actions", "success", true, null, null, null);

    private final RepositoryRepository repositories = mock(RepositoryRepository.class);
    private final PullRequestRepository pullRequests = mock(PullRequestRepository.class);
    private final GitHubCommitCheckRuns checkRuns = mock(GitHubCommitCheckRuns.class);
    private final HeadCheckRunsRecorder recorder = mock(HeadCheckRunsRecorder.class);
    private final GitHubHeadCheckRunsReread reread = new GitHubHeadCheckRunsReread(
            repositories, pullRequests, checkRuns, recorder, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void shouldReadAgainOnlyTheHeadsWhoseRunsHaveNotFinishedSinceARecentRead() {
        var repository = new Repository();
        repository.setId(7L);
        repository.setNameWithOwner(REPOSITORY);
        when(repositories.findByNameWithOwner(REPOSITORY)).thenReturn(Optional.of(repository));
        String lagging = "a".repeat(40);
        var stillRunning = pullRequest(lagging, lagging, RUNNING, NOW.minus(Duration.ofHours(1)));
        var finished = pullRequest("b".repeat(40), "b".repeat(40), PASSED, NOW.minus(Duration.ofHours(1)));
        var dayOld = pullRequest("c".repeat(40), "c".repeat(40), RUNNING, NOW.minus(Duration.ofHours(25)));
        var pushedSince = pullRequest("d".repeat(40), "e".repeat(40), RUNNING, NOW.minus(Duration.ofHours(1)));
        when(pullRequests.findAllByRepository_IdAndStateAndHeadCheckRunsIsNotNull(7L, Issue.State.OPEN))
                .thenReturn(List.of(stillRunning, finished, dayOld, pushedSince));
        var settled = new HeadCheckRuns(lagging, List.of(PASSED), true, NOW, null);
        when(checkRuns.read(3L, REPOSITORY, lagging)).thenReturn(Optional.of(settled));

        reread.reread(3L, REPOSITORY);

        verify(checkRuns).read(3L, REPOSITORY, lagging);
        verify(checkRuns, never()).read(eq(3L), eq(REPOSITORY), eq("b".repeat(40)));
        verify(checkRuns, never()).read(eq(3L), eq(REPOSITORY), eq("c".repeat(40)));
        verify(checkRuns, never()).read(eq(3L), eq(REPOSITORY), eq("e".repeat(40)));
        verify(recorder).observe(eq(7L), eq(settled), any());
    }

    @Test
    void shouldReadNothingForARepositoryThatIsNotStored() {
        when(repositories.findByNameWithOwner(REPOSITORY)).thenReturn(Optional.empty());

        reread.reread(3L, REPOSITORY);

        verify(checkRuns, never()).read(any(Long.class), anyString(), anyString());
    }

    private static PullRequest pullRequest(String runsSha, String head, HeadCheckRuns.Run run, Instant readAt) {
        var pr = new PullRequest();
        pr.setHeadRefOid(head);
        pr.observeHeadCheckRuns(new HeadCheckRuns(runsSha, List.of(run), true, readAt, null));
        return pr;
    }
}
