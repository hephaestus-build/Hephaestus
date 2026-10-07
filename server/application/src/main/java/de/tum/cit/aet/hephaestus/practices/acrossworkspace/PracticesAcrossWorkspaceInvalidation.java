package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import de.tum.cit.aet.hephaestus.practices.observation.ReviewResultsChangedEvent;
import de.tum.cit.aet.hephaestus.workspace.events.WorkspacePrivacyChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Acts on the counts of Practices across the workspace once a change to what they count commits: new review results
 * are counted again in the background, while a result taken back or a change to whose work the workspace may count
 * drops the counts, so the next reader counts again.
 */
@Component
class PracticesAcrossWorkspaceInvalidation {

    private final PracticesAcrossWorkspaceCache cache;

    PracticesAcrossWorkspaceInvalidation(PracticesAcrossWorkspaceCache cache) {
        this.cache = cache;
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onReviewResultsChanged(ReviewResultsChangedEvent event) {
        if (event.retracted()) {
            cache.invalidate(event.workspaceId());
        } else {
            cache.recountLater(event.workspaceId());
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onWorkspacePrivacyChanged(WorkspacePrivacyChangedEvent event) {
        Long workspaceId = event.workspaceId();
        if (workspaceId == null) {
            cache.invalidateAll();
        } else {
            cache.invalidate(workspaceId);
        }
    }
}
