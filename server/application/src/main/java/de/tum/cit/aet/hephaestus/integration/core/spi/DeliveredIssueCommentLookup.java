package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** Provider identities recorded by feedback delivery, across workspaces monitoring the same issue. */
public interface DeliveredIssueCommentLookup {
    List<DeliveredComment> findForIssue(long issueId);

    /** GitHub returns an opaque node id and permalink; GitLab returns a Note global id. */
    record DeliveredComment(String externalRef, @Nullable String url) {}
}
