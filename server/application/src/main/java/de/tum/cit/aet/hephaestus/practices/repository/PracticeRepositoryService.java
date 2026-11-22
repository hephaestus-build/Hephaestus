package de.tum.cit.aet.hephaestus.practices.repository;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.MonitoredRepositoryQuery;
import de.tum.cit.aet.hephaestus.practices.PracticeFiles;
import de.tum.cit.aet.hephaestus.practices.PracticeFolderInvalidException;
import de.tum.cit.aet.hephaestus.practices.PracticeService;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeRepositoryDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeRepositorySyncDTO;
import de.tum.cit.aet.hephaestus.practices.dto.SetPracticeRepositoryRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The workspace's practice repository, and moving a practice between Hephaestus and it (ADR 0118). Each direction
 * reverses the other, and unsetting the repository moves every practice back to Hephaestus. Nothing is deleted.
 */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class PracticeRepositoryService {
    private final PracticeRepositorySettingRepository settings;
    private final MonitoredRepositoryQuery monitored;
    private final PracticeRepositoryFolders folders;
    private final PracticeFiles files;
    private final PracticeService practices;
    private final PracticeRepositoryMove move;
    private final PracticeRepositorySync sync;
    private final WorkspaceQueries workspaces;
    private final ConfigAuditPort audit;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    @Transactional(readOnly = true)
    public Optional<PracticeRepositoryDTO> get(WorkspaceContext ctx) {
        return settings.findById(ctx.id()).map(setting -> view(ctx.id(), setting));
    }

    /**
     * Sets the practice repository. Choosing another repository or directory moves every practice edited in the old one
     * back to Hephaestus first, since the new one may not hold their folders, and forgets the report of the last sync.
     */
    @Transactional
    public PracticeRepositoryDTO set(WorkspaceContext ctx, SetPracticeRepositoryRequestDTO request) {
        lock(ctx);
        long repositoryItemId = Long.parseLong(request.repositoryItemId());
        if (!monitored.repositories(ctx.id()).containsKey(repositoryItemId))
            throw new InvalidInputException("Choose a repository that this workspace monitors.");
        String directory =
                request.directory() == null ? PracticeRepositorySetting.DEFAULT_DIRECTORY : request.directory();
        var existing = settings.findById(ctx.id()).orElse(null);
        if (existing != null) {
            var before = PracticeRepositorySnapshot.of(existing);
            if (existing.getRepositoryItemId() != repositoryItemId
                    || !existing.getDirectory().equals(directory)) {
                practices.moveAllToHephaestus(ctx);
                existing.forgetSync();
            }
            existing.setRepositoryItemId(repositoryItemId);
            existing.setDirectory(directory);
            settings.save(existing);
            audit.record(ConfigAuditEntry.updated(
                    ConfigAuditEntityType.PRACTICE_REPOSITORY,
                    ctx.id(),
                    ctx.id(),
                    before,
                    PracticeRepositorySnapshot.of(existing)));
            return view(ctx.id(), existing);
        }
        var setting = new PracticeRepositorySetting();
        setting.setWorkspace(Objects.requireNonNull(entityManager).getReference(Workspace.class, ctx.id()));
        setting.setRepositoryItemId(repositoryItemId);
        setting.setDirectory(directory);
        settings.save(setting);
        audit.record(ConfigAuditEntry.created(
                ConfigAuditEntityType.PRACTICE_REPOSITORY, ctx.id(), ctx.id(), PracticeRepositorySnapshot.of(setting)));
        return view(ctx.id(), setting);
    }

    /** Unsets the practice repository. Every practice edited there is edited in Hephaestus again, with its versions. */
    @Transactional
    public void unset(WorkspaceContext ctx) {
        lock(ctx);
        var setting = settings.findById(ctx.id())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "This workspace has no practice repository."));
        practices.moveAllToHephaestus(ctx);
        audit.record(ConfigAuditEntry.deleted(
                ConfigAuditEntityType.PRACTICE_REPOSITORY, ctx.id(), ctx.id(), PracticeRepositorySnapshot.of(setting)));
        settings.delete(setting);
    }

    /**
     * Move to repository: reads the practice's folder at the head of the default branch, and moves the practice there
     * only when the folder is valid. A missing or broken folder would otherwise make the next sync treat the practice as
     * removed. It runs with no transaction, because the checkout fetches from the provider.
     */
    public void moveToRepository(WorkspaceContext ctx, String slug) {
        // An unknown practice is refused before the checkout fetches anything.
        practices.getPractice(ctx, slug);
        var folder = folders.read(ctx.id(), slug)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "The default branch of the practice repository has no folder for this practice. Commit the"
                                + " files that Download as files gives, then move it."));
        var read = files.read(slug, folder.files());
        var document = read.document();
        if (document == null) throw new PracticeFolderInvalidException(read.problems());
        move.apply(ctx, slug, document, folder);
    }

    /**
     * Sync now: reads every practice folder at the head of the default branch, checks each one, and applies them as
     * {@link PracticeRepositorySync} describes. Like a move, it reads with no transaction, because the checkout fetches
     * from the provider.
     *
     * @throws ResponseStatusException 409 when the workspace has no practice repository, it cannot be read now, or the
     *     setting changed while the folders were read
     */
    public PracticeRepositorySyncDTO sync(WorkspaceContext ctx) {
        var tree = folders.readAll(ctx.id());
        var reads = new TreeMap<String, PracticeFiles.Read>();
        tree.folders().forEach((slug, folder) -> reads.put(slug, files.read(slug, folder)));
        return sync.apply(ctx, tree, reads);
    }

    /** Edit in Hephaestus: reverses {@link #moveToRepository}. The practice keeps its current version. */
    public void moveToHephaestus(WorkspaceContext ctx, String slug) {
        practices.moveToHephaestus(ctx, slug);
    }

    /** Set, unset and a move each lock the workspace row first, so they run one after another. */
    private void lock(WorkspaceContext ctx) {
        if (workspaces.findByIdForUpdate(ctx.id()).isEmpty())
            throw new EntityNotFoundException("Workspace", ctx.slug());
    }

    private PracticeRepositoryDTO view(long workspaceId, PracticeRepositorySetting setting) {
        var repository = monitored.repositories(workspaceId).get(setting.getRepositoryItemId());
        var commit = setting.getSyncCommit();
        var syncedAt = setting.getSyncedAt();
        var report = setting.getSyncReport();
        return new PracticeRepositoryDTO(
                String.valueOf(setting.getRepositoryItemId()),
                repository == null ? null : repository.nameWithOwner(),
                repository == null ? null : repository.defaultBranch(),
                setting.getDirectory(),
                commit == null || syncedAt == null || report == null
                        ? null
                        : new PracticeRepositorySyncDTO(commit, syncedAt, report.practices(), report.problems()));
    }
}
