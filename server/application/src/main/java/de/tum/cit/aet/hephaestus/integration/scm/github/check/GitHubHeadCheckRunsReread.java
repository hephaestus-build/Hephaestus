package de.tum.cit.aet.hephaestus.integration.scm.github.check;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.HeadCheckRuns;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.HeadCheckRunsRecorder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Reads the check runs of an open pull request's head again when its stored list has not finished and last changed
 * within the last day. A read can lag its hook, and otherwise only the next suite or status of the head that ends reads
 * again, so this read can still record that the checks finished. A list that stays unfinished for a day is left as
 * it is.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GitHubHeadCheckRunsReread {

    static final Duration RECENT = Duration.ofHours(24);

    private final RepositoryRepository repositories;
    private final PullRequestRepository pullRequests;
    private final GitHubCommitCheckRuns checkRuns;
    private final HeadCheckRunsRecorder recorder;
    private final Clock clock;

    /** Reads the heads of one repository again. A failure is logged and leaves the scope's sync running. */
    public void reread(long scopeId, String nameWithOwner) {
        try {
            rereadHeads(scopeId, nameWithOwner);
        } catch (RuntimeException e) {
            log.warn(
                    "Check run re-read failed: repoName={}, error={}",
                    sanitizeForLog(nameWithOwner),
                    e.getClass().getSimpleName());
        }
    }

    private void rereadHeads(long scopeId, String nameWithOwner) {
        var repository = repositories.findByNameWithOwner(nameWithOwner).orElse(null);
        if (repository == null) return;
        var since = clock.instant().minus(RECENT);
        var heads = new LinkedHashSet<String>();
        for (var pr : pullRequests.findAllByRepository_IdAndStateAndHeadCheckRunsIsNotNull(
                repository.getId(), Issue.State.OPEN)) {
            HeadCheckRuns runs = pr.getHeadCheckRuns();
            if (runs != null
                    && runs.sha().equals(pr.getHeadRefOid())
                    && !runs.finished()
                    && runs.observedAt().isAfter(since)) heads.add(runs.sha());
        }
        var context = ProcessingContext.forSync(scopeId, repository);
        for (String sha : heads) {
            checkRuns
                    .read(scopeId, nameWithOwner, sha)
                    .ifPresent(runs -> recorder.observe(repository.getId(), runs, context));
        }
    }
}
