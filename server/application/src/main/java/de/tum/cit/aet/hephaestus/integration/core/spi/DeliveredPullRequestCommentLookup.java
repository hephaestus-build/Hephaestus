package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.Set;

/**
 * The synchronized comments on one pull request that feedback delivery in one workspace recorded posting, whatever
 * became of that feedback since. Only a recorded provider identity counts, never a marker or an author.
 */
public interface DeliveredPullRequestCommentLookup {
    CommentIds findForPullRequest(long workspaceId, long pullRequestId);

    /** Native ids of the mirrored ordinary comments ({@code general}) and line comments ({@code inline}). */
    record CommentIds(Set<Long> general, Set<Long> inline) {}
}
