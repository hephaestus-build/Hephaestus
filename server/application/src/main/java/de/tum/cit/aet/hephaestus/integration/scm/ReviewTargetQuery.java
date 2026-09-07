package de.tum.cit.aet.hephaestus.integration.scm;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * SCM identity snapshots. Callers must authorize workspace access separately.
 * Target lookups include deleted work and return empty when the work or its repository is absent.
 */
public interface ReviewTargetQuery {
    /** Excludes pull requests. */
    Optional<Target> findIssue(long issueId);

    Optional<Target> findPullRequest(long pullRequestId);

    boolean reviewMatchesTarget(long reviewId, long pullRequestId, long reviewerId);

    record Target(
            long repositoryId,
            String repositoryFullName,
            int number,
            @Nullable Long authorId,
            boolean deleted) {}
}
