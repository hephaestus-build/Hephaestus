package de.tum.cit.aet.hephaestus.practices.repository;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.PracticeService;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogPracticeDocument;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Applies Move to repository, once its folder was read outside a transaction. The setting the folder came from must
 * still be the workspace's: a set or unset in between would leave the practice bound to a repository that does not hold
 * its folder, and the next sync would take it for removed. Set, unset and this move each lock the workspace row first,
 * so they run one after another.
 */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
class PracticeRepositoryMove {
    private final PracticeRepositorySettingRepository settings;
    private final PracticeService practices;
    private final WorkspaceQueries workspaces;

    @Transactional
    public void apply(
            WorkspaceContext ctx, String slug, CatalogPracticeDocument document, PracticeRepositoryFolders.Folder folder) {
        if (workspaces.findByIdForUpdate(ctx.id()).isEmpty())
            throw new EntityNotFoundException("Workspace", ctx.slug());
        var setting = settings.findById(ctx.id()).orElse(null);
        if (setting == null
                || setting.getRepositoryItemId() != folder.repositoryItemId()
                || !setting.getDirectory().equals(folder.directory())
                || setting.getRevision() != folder.revision())
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The practice repository changed while its folder was read. Try again.");
        practices.moveToRepository(ctx, slug, document, folder.commit());
    }
}
