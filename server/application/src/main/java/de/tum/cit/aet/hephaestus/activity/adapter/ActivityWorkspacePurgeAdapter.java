package de.tum.cit.aet.hephaestus.activity.adapter;

import de.tum.cit.aet.hephaestus.activity.ActivityAutomationRepository;
import de.tum.cit.aet.hephaestus.activity.ActivityEventRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import org.springframework.stereotype.Component;

/**
 * Adapter that handles activity event cleanup when a workspace is purged.
 *
 * <p>This contributor deletes all activity events associated with a workspace
 * during the purge operation. Activity events can be large in number, so this
 * uses a bulk delete query for efficiency.
 */
@Component
public class ActivityWorkspacePurgeAdapter implements WorkspacePurgeContributor {

    private final ActivityEventRepository activityEventRepository;
    private final ActivityAutomationRepository automation;

    public ActivityWorkspacePurgeAdapter(
            ActivityEventRepository activityEventRepository, ActivityAutomationRepository automation) {
        this.activityEventRepository = activityEventRepository;
        this.automation = automation;
    }

    @Override
    public void deleteWorkspaceData(Long workspaceId) {
        automation.deleteAllByWorkspace_Id(workspaceId);
        activityEventRepository.deleteAllByWorkspaceId(workspaceId);
    }

    @Override
    public int getOrder() {
        // Activity events should be deleted after workspace settings but before
        // repository monitors (which activity events may reference indirectly)
        return 100;
    }
}
