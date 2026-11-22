package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogPracticeDocument;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDraftDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeFileProblemDTO;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeState;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saves practice folders to be tried, and lists, activates and deletes draft practices (ADR 0118 decision 7). A folder
 * whose slug the workspace lacks becomes a draft practice. A draft takes the folder as its next version. A practice in
 * use keeps its current version, and the folder becomes a version beside it to try.
 */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class PracticeDraftService {
    private final PracticeFiles files;
    private final PracticeService practices;
    private final PracticeVersionService versions;
    private final PracticeDrafts drafts;
    private final PracticeRepository rows;
    private final PracticeGroupRepository groups;

    @Transactional
    public PracticeDraftDTO save(WorkspaceContext ctx, String slug, Map<String, String> folder) {
        var read = files.read(slug, folder);
        var document = read.document();
        if (document == null) throw new PracticeFolderInvalidException(read.problems());
        var warnings = new ArrayList<PracticeFileProblemDTO>();
        var groupSlug = document.groupSlug();
        if (groupSlug != null
                && groups.findByWorkspaceIdAndSlug(ctx.id(), groupSlug).isEmpty()) {
            warnings.add(new PracticeFileProblemDTO(
                    PracticeFiles.PRACTICE,
                    null,
                    null,
                    PracticeFileProblemDTO.UNKNOWN_GROUP,
                    "This workspace has no group " + groupSlug + ". The practice stays ungrouped."));
            document = new CatalogPracticeDocument(document.kind(), null, document.definition());
        }
        var existing = drafts.find(ctx.id(), slug).orElse(null);
        if (existing == null) {
            var created = practices.createDraft(ctx, slug, document);
            return view(created, created.getCurrentVersion(), warnings);
        }
        if (!existing.getKind().equals(document.kind()))
            throw new InvalidInputException("A practice keeps the kind of work that it reviews.");
        if (existing.getState() == PracticeState.DRAFT) {
            var draft = document;
            var version = drafts.withDrafts(() -> practices.updateDraft(ctx, existing, draft));
            return view(existing, version, warnings);
        }
        var tried = versions.tryOut(
                existing,
                document.definition(),
                SecurityUtils.getCurrentAccountId().orElse(null));
        practices.recordUnchanged(ctx, existing);
        return view(existing, tried, warnings);
    }

    @Transactional(readOnly = true)
    public List<PracticeDraftDTO> list(WorkspaceContext ctx) {
        return drafts
                .withDrafts(() -> rows.findByWorkspaceIdAndStateOrderBySlugAsc(ctx.id(), PracticeState.DRAFT))
                .stream()
                .map(draft -> view(draft, draft.getCurrentVersion(), List.of()))
                .toList();
    }

    @Transactional
    public void activate(WorkspaceContext ctx, String slug) {
        drafts.withDrafts(() -> practices.activate(ctx, slug));
    }

    @Transactional
    public void delete(WorkspaceContext ctx, String slug) {
        drafts.withDrafts(() -> {
            practices.deleteDraft(ctx, slug);
            return slug;
        });
    }

    private PracticeDraftDTO view(Practice practice, PracticeVersion version, List<PracticeFileProblemDTO> warnings) {
        var group = practice.getGroup();
        return new PracticeDraftDTO(
                practice.getSlug(),
                versions.definitionOf(version).name(),
                practice.getKind().value(),
                group == null ? null : group.getSlug(),
                practice.getState(),
                String.valueOf(version.getId()),
                version.getNumber(),
                Objects.equals(version.getId(), practice.getCurrentVersion().getId()),
                List.copyOf(warnings));
    }
}
