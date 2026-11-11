package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogLock;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.events.WorkspaceCreatedEvent;
import java.time.Instant;
import java.util.Set;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnServerRole
public class PracticeCatalogInstallationManager {
    private final CatalogService catalog;
    private final CatalogLock lock;
    private final WorkspaceRepository workspaces;
    private final PracticeGroupService groups;
    private final PracticeGroupRepository groupRepository;
    private final PracticeService practices;
    private final PracticeRepository practiceRepository;

    public PracticeCatalogInstallationManager(
            CatalogService catalog,
            CatalogLock lock,
            WorkspaceRepository workspaces,
            PracticeGroupService groups,
            PracticeGroupRepository groupRepository,
            PracticeService practices,
            PracticeRepository practiceRepository) {
        this.catalog = catalog;
        this.lock = lock;
        this.workspaces = workspaces;
        this.groups = groups;
        this.groupRepository = groupRepository;
        this.practices = practices;
        this.practiceRepository = practiceRepository;
    }

    @EventListener
    @Transactional
    public void onWorkspaceCreated(WorkspaceCreatedEvent event) {
        installCatalog(event.workspaceId());
    }

    @Transactional
    public void install(Long workspaceId) {
        installCatalog(workspaceId);
    }

    private void installCatalog(Long workspaceId) {
        lock.acquire();
        var workspace = workspaces.findByIdForUpdate(workspaceId).orElseThrow();
        if (workspace.getPracticeCatalogInstalledAt() != null) return;
        var context = WorkspaceContext.fromWorkspace(workspace, Set.of(), null);
        var offered = catalog.effectiveCatalog();
        for (var group : offered.installableGroups()) {
            if (!groupRepository.existsByWorkspaceIdAndSlug(workspaceId, group.slug()))
                groups.createGroupFromCatalog(context, group.slug(), group.effective(), group.position());
        }
        for (var practice : offered.installablePractices()) {
            if (!practiceRepository.existsByWorkspaceIdAndSlug(workspaceId, practice.slug()))
                practices.createPracticeFromCatalog(context, practice.slug(), practice.effective());
        }
        workspace.setPracticeCatalogInstalledAt(Instant.now());
        workspaces.save(workspace);
    }
}
