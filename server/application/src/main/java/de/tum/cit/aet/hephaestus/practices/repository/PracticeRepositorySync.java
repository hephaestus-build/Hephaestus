package de.tum.cit.aet.hephaestus.practices.repository;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDrafts;
import de.tum.cit.aet.hephaestus.practices.PracticeFiles;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeService;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.ReviewedBy;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogPracticeDocument;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeFileProblemDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeRepositorySyncDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeSyncResultDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeSyncStatus;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeEditedIn;
import de.tum.cit.aet.hephaestus.practices.model.PracticeState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedByKind;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies a sync of the practice repository, once its folders were read and checked outside a transaction (ADR 0118).
 * It runs under the workspace lock, as set, unset and a move do, and only under the setting that the folders were read
 * under. A broken folder or directory never removes, retires or edits a practice, and a second sync of the same commit
 * changes nothing.
 */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
class PracticeRepositorySync {
    private final PracticeRepositorySettingRepository settings;
    private final PracticeService practices;
    private final PracticeRepository rows;
    private final PracticeDrafts drafts;
    private final PracticeGroupRepository groups;
    private final PracticeVersionService versions;
    private final WorkspaceQueries workspaces;
    private final JsonMapper mapper;
    private final Clock clock;

    /**
     * Applies the folders of {@code tree} and stores the report.
     *
     * @param reads the check of each folder of {@code tree}, by folder name
     * @throws ResponseStatusException 409 when the setting changed while the folders were read
     */
    @Transactional
    public PracticeRepositorySyncDTO apply(
            WorkspaceContext ctx, PracticeRepositoryFolders.Tree tree, Map<String, PracticeFiles.Read> reads) {
        if (workspaces.findByIdForUpdate(ctx.id()).isEmpty())
            throw new EntityNotFoundException("Workspace", ctx.slug());
        if (!tree.readUnder().isCurrent(settings.findById(ctx.id()).orElse(null)))
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The practice repository changed while its folders were read. Try again.");
        var results = new ArrayList<PracticeSyncResultDTO>();
        if (tree.problems().isEmpty()) {
            Map<String, Practice> existing = drafts.withDrafts(() -> rows.findByWorkspaceId(ctx.id())).stream()
                    .collect(Collectors.toMap(Practice::getSlug, Function.identity()));
            tree.unreadable().forEach((slug, problems) -> results.add(invalid(slug, problems)));
            reads.forEach((slug, read) -> results.add(folder(ctx, slug, read, existing.get(slug), tree.commit())));
            for (var practice : existing.values()) {
                var slug = practice.getSlug();
                if (practice.getEditedIn() != PracticeEditedIn.REPOSITORY
                        || practice.getState() != PracticeState.ACTIVE
                        || reads.containsKey(slug)
                        || tree.unreadable().containsKey(slug)) continue;
                retire(ctx, practice, tree.commit());
                results.add(result(slug, PracticeSyncStatus.RETIRED));
            }
            results.sort(Comparator.comparing(PracticeSyncResultDTO::slug));
        }
        var report = new PracticeRepositorySetting.SyncReport(List.copyOf(results), tree.problems());
        // The column keeps microseconds, so the answer equals what a later read returns.
        var syncedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        settings.recordSync(ctx.id(), tree.commit(), syncedAt, mapper.writeValueAsString(report));
        return new PracticeRepositorySyncDTO(tree.commit(), syncedAt, report.practices(), report.problems());
    }

    private PracticeSyncResultDTO folder(
            WorkspaceContext ctx, String slug, PracticeFiles.Read read, @Nullable Practice practice, String commit) {
        var document = read.document();
        if (document == null) return invalid(slug, read.problems());
        if (practice != null
                && practice.getState() == PracticeState.ACTIVE
                && practice.getEditedIn() == PracticeEditedIn.HEPHAESTUS)
            return result(slug, PracticeSyncStatus.EDITED_IN_HEPHAESTUS);
        var problem = problem(ctx, document, practice);
        if (problem != null) return invalid(slug, List.of(problem));
        if (practice == null) {
            practices.createFromRepository(ctx, slug, document, commit);
            return result(slug, PracticeSyncStatus.CREATED);
        }
        if (practice.getState() == PracticeState.DRAFT) {
            practices.takeOverDraft(ctx, practice, document, commit);
            return result(slug, PracticeSyncStatus.TAKEN_OVER);
        }
        // The definition compares as moveToRepository compares it, so an equal folder adds no version.
        var group = practice.getGroup();
        if (practice.getCurrentVersion().getDefinition().equals(mapper.valueToTree(document.definition()))
                && Objects.equals(group == null ? null : group.getSlug(), document.groupSlug()))
            return result(slug, PracticeSyncStatus.UNCHANGED);
        practices.moveToRepository(ctx, slug, document, commit);
        return result(slug, PracticeSyncStatus.UPDATED);
    }

    /**
     * The folder of a practice edited in the repository is gone: the practice gets a version that nobody reviews, read
     * at the commit that lacks the folder. Its history and observations stay, which a delete would lose. As Reviewed by
     * Nobody in the editor, the reads, script, scan and limits stay unused, so a folder that comes back restores them.
     */
    private void retire(WorkspaceContext ctx, Practice practice, String commit) {
        var current = versions.definitionOf(practice.getCurrentVersion());
        if (current.reviewedBy().by() == ReviewedByKind.NOBODY) return;
        var retired = new PracticeDefinition(
                current.name(),
                current.reviewOn(),
                current.appliesWhen(),
                current.about(),
                current.reads(),
                new ReviewedBy(ReviewedByKind.NOBODY, null, current.reviewedBy().limits()),
                current.criteria(),
                current.script(),
                current.scan(),
                current.lineNotes(),
                current.guidance());
        var group = practice.getGroup();
        practices.moveToRepository(
                ctx,
                practice.getSlug(),
                new CatalogPracticeDocument(practice.getKind(), group == null ? null : group.getSlug(), retired),
                commit);
    }

    /** What keeps a valid folder from changing the workspace: a kind that the practice does not review, or a group. */
    private @Nullable PracticeFileProblemDTO problem(
            WorkspaceContext ctx, CatalogPracticeDocument document, @Nullable Practice practice) {
        if (practice != null && !practice.getKind().equals(document.kind()))
            return new PracticeFileProblemDTO(
                    PracticeFiles.PRACTICE,
                    null,
                    null,
                    PracticeFileProblemDTO.KIND_CHANGED,
                    "A practice keeps the kind of work that it reviews, "
                            + practice.getKind().value() + ". Name that kind, or add the folder under a new slug.");
        var groupSlug = document.groupSlug();
        if (groupSlug != null
                && groups.findByWorkspaceIdAndSlug(ctx.id(), groupSlug).isEmpty())
            return new PracticeFileProblemDTO(
                    PracticeFiles.PRACTICE,
                    null,
                    null,
                    PracticeFileProblemDTO.UNKNOWN_GROUP,
                    "This workspace has no group " + groupSlug + ". Create it, or remove it from the folder.");
        return null;
    }

    private static PracticeSyncResultDTO result(String slug, PracticeSyncStatus status) {
        return new PracticeSyncResultDTO(slug, status, List.of());
    }

    private static PracticeSyncResultDTO invalid(String slug, List<PracticeFileProblemDTO> problems) {
        return new PracticeSyncResultDTO(slug, PracticeSyncStatus.INVALID, List.copyOf(problems));
    }
}
