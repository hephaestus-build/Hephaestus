package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.Set;

/**
 * The synchronized comments on one pull request that feedback delivery in one workspace recorded posting, whatever
 * became of that feedback since. Only a recorded provider identity counts, never a marker or an author.
 */
public interface DeliveredPullRequestCommentLookup {
    CommentIds findForPullRequest(long workspaceId, long pullRequestId, ItemKind kind);

    /** Native ids of the mirrored ordinary comments ({@code general}), line comments ({@code inline}) and submitted reviews ({@code reviews}). */
    record CommentIds(Set<Long> general, Set<Long> inline, Set<Long> reviews) {}
}
