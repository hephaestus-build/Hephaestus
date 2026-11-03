package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The follow-up reads of one sync run: the lists that a work item's first page left to read, and what came of them. A
 * work item stays recorded as not read whole until each of its lists reached its end.
 */
public final class DiscussionReads {
    private final Map<Long, Integer> open = new HashMap<>();
    private final Set<Long> stopped = new HashSet<>();
    private SyncResult.Status worst = SyncResult.Status.COMPLETED;
    private boolean rateLimited;

    /** One more list of this work item has pages left to read. */
    public void expect(long pullRequestId) {
        open.merge(pullRequestId, 1, Integer::sum);
    }

    public void record(long pullRequestId, SyncResult read) {
        open.merge(pullRequestId, -1, Integer::sum);
        if (read.status() != SyncResult.Status.COMPLETED) stop(pullRequestId, read.status());
    }

    /** A read of this work item ended before its list did, in a way that no follow-up settles. */
    public void stop(long pullRequestId, SyncResult.Status status) {
        stopped.add(pullRequestId);
        if (status.isWorseThan(worst)) worst = status;
        rateLimited |= status == SyncResult.Status.ABORTED_RATE_LIMIT;
    }

    /** Whether a rate limit ended a read, so that the run stops asking until its window is read again. */
    public boolean rateLimited() {
        return rateLimited;
    }

    public void settle(PullRequestRepository pullRequests) {
        open.forEach((id, lists) -> {
            if (lists == 0 && !stopped.contains(id)) pullRequests.recordDiscussionRead(id, true);
        });
    }

    public SyncResult.Status status() {
        return worst;
    }
}
