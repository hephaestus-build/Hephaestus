package de.tum.cit.aet.hephaestus.activity.adapter;

import de.tum.cit.aet.hephaestus.activity.ActivityEventRepository;
import de.tum.cit.aet.hephaestus.core.event.RepositoryWithdrawnFromWorkspaceEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Removes the Activity a workspace recorded from a repository it had to give up. */
@Component
public class ActivityRepositoryWithdrawal {

    private final ActivityEventRepository activityEvents;

    public ActivityRepositoryWithdrawal(ActivityEventRepository activityEvents) {
        this.activityEvents = activityEvents;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onWithdrawn(RepositoryWithdrawnFromWorkspaceEvent event) {
        if (!event.repositoryIds().isEmpty()) {
            activityEvents.deleteByWorkspaceIdAndRepositoryIds(event.workspaceId(), event.repositoryIds());
        }
    }
}
