package de.tum.cit.aet.hephaestus.notification.push;

import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import org.springframework.stereotype.Component;

/** A purged workspace takes its queued and recorded push notifications with it. */
@Component
class PushWorkspacePurgeAdapter implements WorkspacePurgeContributor {

    private final PushNotificationRepository notificationRepository;

    PushWorkspacePurgeAdapter(PushNotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Override
    public void deleteWorkspaceData(Long workspaceId) {
        notificationRepository.deleteByWorkspaceId(workspaceId);
    }
}
