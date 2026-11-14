package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.core.event.RepositoryMonitoringChangedEvent;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScmRepositoryItemLifecycle {
    private final ItemIndexService writer;
    private final RepositoryRepository repositories;
    private final ScmRepositoryAccess access;
    private final ScmWorkItemIndex work;
    private final Clock clock;

    @EventListener
    @Transactional
    public void onMonitoringChanged(RepositoryMonitoringChangedEvent event) {
        for (long repositoryId :
                event.repositoryIds().stream().distinct().sorted().toList()) {
            if (!repositories.isMonitored(event.workspaceId(), repositoryId)) {
                if (event.retainIndex()) continue;
                writer.findSource(event.workspaceId(), ScmItemEvents.REPOSITORY.value(), repositoryId)
                        .ifPresent(item -> writer.remove(event.workspaceId(), item.itemId(), clock.instant()));
                continue;
            }
            repositories.claimForIndex(repositoryId);
            var repository = repositories.findById(repositoryId).orElseThrow();
            try {
                access.authorize(event.workspaceId(), repository);
            } catch (ItemCaptureException denied) {
                if (denied.code() == ItemCaptureCode.ITEM_NOT_VISIBLE) continue;
                throw denied;
            }
            var item = writer.upsert(
                    new ItemRegistration(event.workspaceId(), ScmItemEvents.REPOSITORY, repositoryId, null, null));
            writer.restore(event.workspaceId(), item.itemId());
            work.reconcile(item);
        }
    }
}
