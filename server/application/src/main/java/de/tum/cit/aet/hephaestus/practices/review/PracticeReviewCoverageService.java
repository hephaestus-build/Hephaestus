package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.MonitoredRepositoryQuery;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewScopeRepositoryDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.WorkspaceReviewScopeDTO;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewRepositoryTarget;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeMode;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceReviewScope;
import de.tum.cit.aet.hephaestus.workspace.spi.MembershipReviewSelection;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceCommands;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries.ReviewWorkspace;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PracticeReviewCoverageService {
    static final int ESTIMATE_WINDOW_DAYS = 30;
    private final WorkspaceQueries workspaceQueries;
    private final WorkspaceCommands workspaceCommands;
    private final ProductItemRepository items;
    private final MonitoredRepositoryQuery monitored;
    private final MembershipReviewSelection selections;

    public Map<String, List<String>> generatedPaths(ReviewWorkspace workspace) {
        var indexed = monitored.repositories(workspace.id()).values().stream()
                .collect(Collectors.toMap(ref -> ref.monitorId(), ref -> ref.repositoryItemId()));
        return workspaceQueries.repositorySettings(workspace.id()).stream()
                .filter(m -> indexed.containsKey(m.id()) && !m.generatedPaths().isEmpty())
                .collect(Collectors.toUnmodifiableMap(
                        m -> Long.toString(Objects.requireNonNull(indexed.get(m.id()))),
                        m -> List.copyOf(m.generatedPaths())));
    }

    @Transactional
    public void patchGeneratedPaths(ReviewWorkspace workspace, Map<String, List<String>> requested) {
        var indexed = monitored.repositories(workspace.id());
        var monitors = workspaceQueries.repositorySettings(workspace.id()).stream()
                .collect(Collectors.toMap(WorkspaceQueries.RepositorySettings::id, Function.identity()));
        var validated = new HashMap<Long, List<String>>();
        requested.forEach((key, patterns) -> {
            long itemId = positiveId(key);
            var repository = indexed.get(itemId);
            if (repository == null || !monitors.containsKey(repository.monitorId()))
                throw new InvalidReviewScopeException("Select a repository this workspace monitors.");
            validated.put(repository.monitorId(), GeneratedPaths.normalize(patterns));
        });
        workspaceCommands.patchGeneratedPaths(workspace.id(), validated);
    }

    public static long positiveId(String value) {
        if (!value.matches("[1-9][0-9]*"))
            throw new InvalidReviewScopeException("Use a positive account or repository item id.");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new InvalidReviewScopeException("Use a positive account or repository item id.", invalid);
        }
    }

    public Set<Long> eligibleAccountIds(long workspaceId) {
        return selections.eligibleAccountIds(workspaceId);
    }

    public WorkspaceReviewScope scope(ReviewWorkspace workspace) {
        var indexed = monitored.repositories(workspace.id()).values().stream()
                .collect(Collectors.toMap(ref -> ref.monitorId(), ref -> ref.repositoryItemId()));
        var repositories = workspaceQueries.reviewRepositoryTargets(workspace.id()).stream()
                .filter(t -> indexed.containsKey(t.repositoryMonitorId()))
                .map(t -> new ReviewRepositoryTarget(
                        Objects.requireNonNull(indexed.get(t.repositoryMonitorId())), t.baseBranches()))
                .sorted(Comparator.comparingLong(ReviewRepositoryTarget::repositoryItemId))
                .toList();
        List<Long> selected = selections.selectedAccountIds(workspace.id());
        var s = workspace.reviewSettings();
        return new WorkspaceReviewScope(s.reviewScopeRepositories(), s.reviewScopePeople(), repositories, selected);
    }

    public WorkspaceReviewScopeDTO wireScope(ReviewWorkspace workspace) {
        var internal = scope(workspace);
        return new WorkspaceReviewScopeDTO(
                internal.repositoryMode(),
                internal.personMode(),
                internal.repositories().stream()
                        .map(t -> new ReviewScopeRepositoryDTO(Long.toString(t.repositoryItemId()), t.baseBranches()))
                        .toList(),
                internal.accountIds().stream().map(Object::toString).toList());
    }

    public WorkspaceReviewScope fromWire(long workspaceId, WorkspaceReviewScopeDTO dto) {
        var indexed = monitored.repositories(workspaceId);
        var repositories = dto.selectedRepositories().stream()
                .map(t -> {
                    long itemId = positiveId(t.repositoryItemId());
                    if (!indexed.containsKey(itemId))
                        throw new InvalidReviewScopeException("Select a repository this workspace monitors.");
                    return new ReviewRepositoryTarget(itemId, t.baseBranches());
                })
                .toList();
        return new WorkspaceReviewScope(
                dto.repositories(),
                dto.people(),
                repositories,
                dto.accountIds().stream()
                        .map(PracticeReviewCoverageService::positiveId)
                        .toList());
    }

    public boolean admitsRepositoryItem(ReviewWorkspace workspace, long itemId) {
        var repository = monitored.repositories(workspace.id()).get(itemId);
        return repository != null && scope(workspace).admitsRepository(itemId, null, false);
    }

    public PracticeReviewScopeSummaryDTO summary(ReviewWorkspace workspace, int volume) {
        return summary(workspace, scope(workspace), volume);
    }

    private PracticeReviewScopeSummaryDTO summary(ReviewWorkspace workspace, WorkspaceReviewScope scope, int volume) {
        int monitored = workspaceQueries.repositorySettings(workspace.id()).size(),
                eligible = eligibleAccountIds(workspace.id()).size();
        return new PracticeReviewScopeSummaryDTO(
                scope.repositoryMode() == ReviewScopeMode.ALL
                        ? monitored
                        : scope.repositories().size(),
                monitored,
                scope.personMode() == ReviewScopeMode.ALL
                        ? eligible
                        : scope.accountIds().size(),
                eligible,
                volume,
                ESTIMATE_WINDOW_DAYS);
    }

    public PracticeReviewScopePreviewDTO preview(ReviewWorkspace workspace, WorkspaceReviewScope proposed, int volume) {
        validate(workspace.id(), proposed);
        var before = scope(workspace);
        boolean widens = eligibleAccountIds(workspace.id()).stream()
                .anyMatch(id -> proposed.admitsPerson(id) && !before.admitsPerson(id));
        for (var repository : monitored.repositories(workspace.id()).values()) {
            var old = before.repositories().stream()
                    .filter(t -> t.repositoryItemId() == repository.repositoryItemId())
                    .findFirst()
                    .orElse(null);
            var next = proposed.repositories().stream()
                    .filter(t -> t.repositoryItemId() == repository.repositoryItemId())
                    .findFirst()
                    .orElse(null);
            boolean oldAll = before.repositoryMode() == ReviewScopeMode.ALL
                    || (old != null && old.baseBranches().isEmpty());
            boolean nextAll = proposed.repositoryMode() == ReviewScopeMode.ALL
                    || (next != null && next.baseBranches().isEmpty());
            if ((nextAll && !oldAll)
                    || (next != null
                            && !oldAll
                            && (old == null || !old.baseBranches().containsAll(next.baseBranches())))) widens = true;
        }
        return new PracticeReviewScopePreviewDTO(
                summary(workspace, before, volume), summary(workspace, proposed, volume), widens);
    }

    private void validate(long workspaceId, WorkspaceReviewScope requested) {
        var indexed = monitored.repositories(workspaceId);
        if (requested.repositories().stream().anyMatch(t -> !indexed.containsKey(t.repositoryItemId())))
            throw new InvalidReviewScopeException("Select only repositories this workspace monitors.");
        if (!eligibleAccountIds(workspaceId).containsAll(requested.accountIds()))
            throw new InvalidReviewScopeException("Select only eligible workspace members.");
    }

    /** The eligible members and the review scope that every practice of one admission is checked against, read once. */
    public Admission admission(ReviewWorkspace workspace) {
        return new Admission(eligibleAccountIds(workspace.id()), scope(workspace));
    }

    public record Admission(Set<Long> eligible, WorkspaceReviewScope scope) {
        public boolean admits(
                @Nullable Long repository,
                @Nullable String branch,
                @Nullable Long developerId,
                boolean branchRestrictionsApply) {
            return (developerId == null || (eligible.contains(developerId) && scope.admitsPerson(developerId)))
                    && (repository == null || scope.admitsRepository(repository, branch, branchRestrictionsApply));
        }
    }

    public @Nullable Long repositoryItemId(long workspaceId, long itemId) {
        return items.repositoryItemId(workspaceId, itemId);
    }

    @Transactional
    public ReviewWorkspace replace(ReviewWorkspace workspace, WorkspaceReviewScope requested) {
        validate(workspace.id(), requested);
        var indexed = monitored.repositories(workspace.id());
        var desired = new HashMap<Long, List<String>>();
        if (requested.repositoryMode() == ReviewScopeMode.SELECTED)
            requested
                    .repositories()
                    .forEach(t -> desired.put(
                            Objects.requireNonNull(indexed.get(t.repositoryItemId()))
                                    .monitorId(),
                            t.baseBranches()));
        workspaceCommands.replaceReviewScope(
                workspace.id(), requested.repositoryMode(), requested.personMode(), desired);
        selections.replaceSelection(
                workspace.id(),
                requested.personMode() == ReviewScopeMode.SELECTED ? requested.accountIds() : List.of());
        return workspace.withReviewSettings(
                workspace.reviewSettings().applyRollout(requested.repositoryMode(), requested.personMode(), null));
    }
}
