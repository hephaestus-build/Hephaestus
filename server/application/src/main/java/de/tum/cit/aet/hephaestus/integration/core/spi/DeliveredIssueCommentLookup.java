package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Provider identities recorded by feedback delivery, across workspaces monitoring the same issue. */
public interface DeliveredIssueCommentLookup {
    List<DeliveredComment> findForIssue(long issueId);

    /**
     * {@link #findForIssue} for every issue of a repository the workspace monitors, by issue id. An issue with no
     * recorded delivery has no entry.
     */
    Map<Long, List<DeliveredComment>> findForRepository(long workspaceId, long repositoryId);

    /** GitHub returns an opaque node id and permalink; GitLab returns a Note global id. */
    record DeliveredComment(String externalRef, @Nullable String url) {}
}
