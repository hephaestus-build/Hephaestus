package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.auth.AccountReferenceService;
import de.tum.cit.aet.hephaestus.core.auth.dto.AccountRefDTO;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogEntryState;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogPracticeDocument;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogService;
import de.tum.cit.aet.hephaestus.practices.catalog.EffectiveCatalog;
import de.tum.cit.aet.hephaestus.practices.dto.CatalogOriginDTO;
import de.tum.cit.aet.hephaestus.practices.dto.FeedbackModeAssignmentDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeVersionDTO;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.practices.review.feedbackmode.FeedbackModeResolver;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@ConditionalOnServerRole
public class CatalogOriginPresenter {
    private final CatalogService catalog;
    private final PracticeReviewDigest digest;
    private final PracticeVersionService versions;
    private final WorkspaceReviewDefaultsProvider defaults;
    private final PracticeVersionRepository versionRepository;
    private final AccountReferenceService accountReferences;

    public PracticeVersionDTO viewCurrent(Practice practice) {
        return Objects.requireNonNull(
                currentViews(Objects.requireNonNull(practice.getWorkspace().getId()), List.of(practice))
                        .get(practice.getId()));
    }

    public List<PracticeVersionDTO> history(Practice practice) {
        long workspaceId = Objects.requireNonNull(practice.getWorkspace().getId());
        var rows = versionRepository.findByWorkspaceIdAndPracticeIdOrderByNumberAsc(workspaceId, practice.getId());
        var creators = creators(workspaceId, rows);
        List<PracticeVersionDTO> history = new ArrayList<>();
        PracticeVersion previous = null;
        for (PracticeVersion version : rows) {
            var comparison = previous == null
                    ? null
                    : new PracticeVersionRepository.PreviousDefinition(
                            Objects.requireNonNull(version.getId()),
                            previous.getDefinition(),
                            previous.getReviewDigest());
            history.add(versions.view(version, comparison, createdBy(version, creators)));
            previous = version;
        }
        return history.reversed();
    }

    private Map<Long, AccountRefDTO> creators(long workspaceId, List<PracticeVersion> rows) {
        return accountReferences.references(
                workspaceId,
                rows.stream()
                        .map(PracticeVersion::getCreatedByAccountId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList());
    }

    private static @Nullable AccountRefDTO createdBy(PracticeVersion version, Map<Long, AccountRefDTO> creators) {
        var id = version.getCreatedByAccountId();
        return id == null ? null : creators.get(id);
    }

    private Map<Long, PracticeVersionDTO> currentViews(long workspaceId, List<Practice> practices) {
        if (practices.isEmpty()) return Map.of();
        var current = practices.stream().map(Practice::getCurrentVersion).toList();
        var previous = versionRepository
                .previousDefinitions(
                        workspaceId,
                        current.stream()
                                .map(version -> Objects.requireNonNull(version.getId()))
                                .toList())
                .stream()
                .collect(Collectors.toMap(PracticeVersionRepository.PreviousDefinition::currentVersionId, row -> row));
        var creators = creators(workspaceId, current);
        return practices.stream()
                .collect(Collectors.toMap(practice -> Objects.requireNonNull(practice.getId()), practice -> {
                    var version = practice.getCurrentVersion();
                    return versions.view(version, previous.get(version.getId()), createdBy(version, creators));
                }));
    }

    public PracticeDTO present(Long workspaceId, Practice practice) {
        return presentPractices(workspaceId, List.of(practice)).getFirst();
    }

    private PracticeDTO present(
            Practice practice,
            @Nullable EffectiveCatalog effectiveCatalog,
            @Nullable FeedbackMode mode,
            PracticeVersionDTO current) {
        var effective = FeedbackModeResolver.resolvePractice(practice, mode);
        return new PracticeDTO(
                practice.getId().toString(),
                practice.getSlug(),
                practice.getKind().value(),
                current,
                practice.getGroup() == null ? null : practice.getGroup().getSlug(),
                practice.getPosition(),
                new FeedbackModeAssignmentDTO(practice.getFeedbackMode(), effective.mode(), effective.source()),
                origin(practice, effectiveCatalog),
                practice.getCreatedAt(),
                practice.getUpdatedAt());
    }

    public PracticeGroupDTO present(Long workspaceId, PracticeGroup group) {
        return present(
                group,
                group.getCatalogSlug() == null ? null : catalog.effectiveCatalog(),
                defaults.rawDefaultFeedbackMode(workspaceId));
    }

    private PracticeGroupDTO present(
            PracticeGroup group, @Nullable EffectiveCatalog effectiveCatalog, @Nullable FeedbackMode mode) {
        var effective = FeedbackModeResolver.resolveGroup(group, mode);
        String source = group.getCatalogSlug();
        var offer = source == null
                ? null
                : Objects.requireNonNull(effectiveCatalog).group(source).orElse(null);
        var current = new GroupDefinition(group.getName(), group.getDescription(), group.getIcon(), group.getColor());
        CatalogEntryState state = source == null
                ? CatalogEntryState.YOURS
                : offer == null
                        ? CatalogEntryState.NO_LONGER_SHIPPED
                        : Objects.equals(current, offer.effective())
                                ? CatalogEntryState.FROM_HEPHAESTUS
                                : Objects.equals(group.getCatalogBase(), offer.effective())
                                        ? CatalogEntryState.EDITED_HERE
                                        : CatalogEntryState.UPDATE_WAITING;
        return new PracticeGroupDTO(
                group.getId().toString(),
                group.getSlug(),
                group.getName(),
                group.getDescription(),
                group.getIcon(),
                group.getColor(),
                group.getPosition(),
                group.isShownToDevelopers(),
                new FeedbackModeAssignmentDTO(group.getFeedbackMode(), effective.mode(), effective.source()),
                new CatalogOriginDTO(source, state, List.of(), null),
                group.getCreatedAt(),
                group.getUpdatedAt());
    }

    public List<PracticeDTO> presentPractices(Long workspaceId, List<Practice> practices) {
        var effectiveCatalog = practices.stream().anyMatch(practice -> practice.getCatalogSlug() != null)
                ? catalog.effectiveCatalog()
                : null;
        var mode = defaults.rawDefaultFeedbackMode(workspaceId);
        var current = currentViews(workspaceId, practices);
        return practices.stream()
                .map(practice -> present(
                        practice, effectiveCatalog, mode, Objects.requireNonNull(current.get(practice.getId()))))
                .toList();
    }

    public List<PracticeGroupDTO> presentGroups(Long workspaceId, List<PracticeGroup> groups) {
        var effectiveCatalog =
                groups.stream().anyMatch(group -> group.getCatalogSlug() != null) ? catalog.effectiveCatalog() : null;
        var mode = defaults.rawDefaultFeedbackMode(workspaceId);
        return groups.stream()
                .map(group -> present(group, effectiveCatalog, mode))
                .toList();
    }

    private CatalogOriginDTO origin(Practice practice, @Nullable EffectiveCatalog effectiveCatalog) {
        String source = practice.getCatalogSlug();
        CatalogPracticeDocument base = practice.getCatalogBase();
        if (source == null) return new CatalogOriginDTO(null, CatalogEntryState.YOURS, List.of(), null);
        var current = versions.definitionOf(practice.getCurrentVersion());
        var edits = Arrays.stream(PracticeDefinitionField.values())
                .filter(field -> base == null
                        || !Objects.equals(
                                PracticeDefinitionMerge.value(field, base.definition()),
                                PracticeDefinitionMerge.value(field, current)))
                .toList();
        var offer = Objects.requireNonNull(effectiveCatalog).practice(source).orElse(null);
        CatalogEntryState state = offer == null
                ? CatalogEntryState.NO_LONGER_SHIPPED
                : current.equals(offer.effective().definition())
                        ? CatalogEntryState.FROM_HEPHAESTUS
                        : Objects.equals(base, offer.effective())
                                ? CatalogEntryState.EDITED_HERE
                                : CatalogEntryState.UPDATE_WAITING;
        return new CatalogOriginDTO(
                source,
                state,
                base == null ? List.of() : edits,
                offer == null ? null : digest.of(offer.effective().definition()));
    }
}
