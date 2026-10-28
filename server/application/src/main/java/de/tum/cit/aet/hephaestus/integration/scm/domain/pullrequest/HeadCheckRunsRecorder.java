package de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest;

import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventIdentity;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores the check runs one provider read listed on the pull requests whose head they are about. When the stored runs
 * change to a complete list whose runs all ended, the pull request's checks completed. The occasion this records is
 * keyed by the settled runs ({@link ScmEventIdentity#checks}): a repeated read records no second one, and a re-run on
 * the same head that settles with other results records a new one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HeadCheckRunsRecorder {

    private final PullRequestRepository pullRequestRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** Locks each pull request on the runs' commit, then stores the runs on it. */
    @Transactional
    public void observe(long repositoryId, HeadCheckRuns runs, ProcessingContext context) {
        store(
                pullRequestRepository.findAllForUpdateByRepositoryIdAndHeadRefOid(repositoryId, runs.sha()),
                runs,
                context);
    }

    /** Stores the runs on pull requests the caller has locked in its own transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void observe(List<PullRequest> lockedPullRequests, HeadCheckRuns runs, ProcessingContext context) {
        store(lockedPullRequests, runs, context);
    }

    /** Why stored runs record no completion: the next read of the head may still record it. */
    private static String unfinished(HeadCheckRuns runs) {
        if (!runs.complete()) return "listIncomplete";
        if (runs.runs().isEmpty()) return "noRuns";
        return "runsUnfinished:"
                + runs.runs().stream().filter(run -> !run.finished()).count();
    }

    /** A pull request whose head is another commit is left as it is. */
    private void store(List<PullRequest> lockedPullRequests, HeadCheckRuns runs, ProcessingContext context) {
        for (PullRequest pr : lockedPullRequests) {
            if (!runs.sha().equals(pr.getHeadRefOid()) || !pr.observeHeadCheckRuns(runs)) {
                continue;
            }
            PullRequest stored = pullRequestRepository.save(pr);
            if (runs.finished()) {
                eventPublisher.publishEvent(new ScmDomainEvent.PullRequestChecksCompleted(
                        ScmEventPayload.PullRequestData.from(stored),
                        EventContext.from(context)
                                .withSourceKey(
                                        ScmEventIdentity.checks(stored.getId(), runs.sha(), runs.runsDigest()))));
                log.debug(
                        "Checks completed: prId={}, sha={}, runs={}",
                        stored.getId(),
                        runs.sha(),
                        runs.runs().size());
            } else {
                log.info(
                        "Checks not finished: prId={}, sha={}, reason={}",
                        stored.getId(),
                        runs.sha(),
                        unfinished(runs));
            }
        }
    }
}
