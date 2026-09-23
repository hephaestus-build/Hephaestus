package de.tum.cit.aet.hephaestus.practices.curated;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.practices.GroupDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionValidator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@WorkspaceAgnostic("The instance catalog is global")
public class CuratedCatalogService {

    private static final String CATALOG_PRACTICE = "Catalog practice";
    private static final String CATALOG_GROUP = "Catalog group";

    private final BundledPracticeCatalogLoader loader;
    private final CuratedCatalogLock catalogLock;
    private final CuratedPracticeOverrideRepository practiceOverrides;
    private final CuratedGroupOverrideRepository groupOverrides;
    private final ConfigAuditPort configAudit;
    private final Clock clock;
    private final PracticeDefinitionValidator definitionValidator;

    @Transactional(readOnly = true)
    public EffectiveCatalog catalog() {
        return loadCatalog();
    }

    private EffectiveCatalog loadCatalog() {
        return CuratedCatalogModel.compose(loader.catalog(), groupOverrides.findAll(), practiceOverrides.findAll());
    }

    @Transactional(readOnly = true)
    public CatalogEntry<PracticeDefinition> practice(String slug) {
        return loadPractice(slug);
    }

    private CatalogEntry<PracticeDefinition> loadPractice(String slug) {
        return loadCatalog().practice(slug).orElseThrow(() -> new EntityNotFoundException(CATALOG_PRACTICE, slug));
    }

    @Transactional(readOnly = true)
    public CatalogEntry<GroupDefinition> group(String slug) {
        return loadCatalog().group(slug).orElseThrow(() -> new EntityNotFoundException(CATALOG_GROUP, slug));
    }

    @Transactional
    public CatalogEntry<PracticeDefinition> writePractice(
            String slug, @Nullable EntityTagPrecondition precondition, PracticeDefinition definition) {
        lockCatalog();
        EffectiveCatalog before = loadCatalog();
        CuratedCatalogModel.validatePracticeGroup(before, definition);
        definitionValidator.validate(definition);
        CatalogEntry<PracticeDefinition> entry =
                CuratedCatalogModel.requireEntry(before.practice(slug), CATALOG_PRACTICE, slug, precondition);
        if (entry.overridden() != null && definition.equals(entry.shipped())) {
            clearPracticeDefinition(slug);
            return recordPractice(slug, entry);
        }
        if (entry.effective().equals(definition)) {
            return entry;
        }
        CuratedPracticeOverride override =
                practiceOverrides.findBySlug(slug).orElseGet(() -> new CuratedPracticeOverride(slug, clock.instant()));
        PracticeDefinition shipped = entry.shipped();
        boolean newDefinition = override.definition() == null;
        if (newDefinition && shipped != null) {
            override.adoptBundledBase(shipped);
        } else {
            override.backfillBase(shipped);
        }
        override.write(definition, override.getAcceptedBundledDigest(), clock.instant());
        practiceOverrides.save(override);
        return recordPractice(slug, entry);
    }

    @Transactional
    public CatalogEntry<PracticeDefinition> createPractice(String slug, PracticeDefinition definition) {
        lockCatalog();
        EffectiveCatalog before = loadCatalog();
        CuratedCatalogModel.validatePracticeGroup(before, definition);
        definitionValidator.validate(definition);
        if (before.practice(slug).isPresent()) {
            throw new CuratedCatalogConflictException("A practice with slug '" + slug + "' already exists.");
        }
        Instant now = clock.instant();
        CuratedPracticeOverride override = new CuratedPracticeOverride(slug, now);
        override.write(definition, null, now);
        practiceOverrides.save(override);
        CatalogEntry<PracticeDefinition> created = loadPractice(slug);
        configAudit.record(ConfigAuditEntry.instanceCreated(
                ConfigAuditEntityType.CURATED_PRACTICE, created.slug(), CuratedPracticeSnapshot.of(created)));
        return created;
    }

    @Transactional
    public CatalogEntry<PracticeDefinition> resetPractice(String slug, @Nullable EntityTagPrecondition precondition) {
        lockCatalog();
        CatalogEntry<PracticeDefinition> entry =
                CuratedCatalogModel.requireEntry(loadCatalog().practice(slug), CATALOG_PRACTICE, slug, precondition);
        if (entry.shipped() == null) {
            throw new CuratedCatalogConflictException("Hephaestus ships no definition for '" + slug + "'.");
        }
        practiceOverrides.findBySlug(slug).ifPresent(override -> {
            override.clearDefinition(clock.instant());
            if (override.isEmpty()) {
                practiceOverrides.delete(override);
            } else {
                practiceOverrides.save(override);
            }
        });
        return recordPractice(slug, entry);
    }

    @Transactional
    public CatalogEntry<PracticeDefinition> keepPractice(String slug, @Nullable EntityTagPrecondition precondition) {
        lockCatalog();
        CatalogEntry<PracticeDefinition> entry =
                CuratedCatalogModel.requireEntry(loadCatalog().practice(slug), CATALOG_PRACTICE, slug, precondition);
        if (entry.overridden() == null) {
            return entry;
        }
        practiceOverrides.findBySlug(slug).ifPresent(override -> {
            override.acknowledge(CuratedCatalogModel.digestOf(entry.shipped(), slug), clock.instant());
            if (entry.shipped() != null) {
                override.adoptBundledBase(entry.shipped());
            }
            practiceOverrides.save(override);
        });
        return recordPractice(slug, entry);
    }

    @Transactional
    public CatalogEntry<PracticeDefinition> setPracticeStatus(
            String slug, @Nullable EntityTagPrecondition precondition, CuratedStatus status) {
        lockCatalog();
        CatalogEntry<PracticeDefinition> entry =
                CuratedCatalogModel.requireEntry(loadCatalog().practice(slug), CATALOG_PRACTICE, slug, precondition);
        if (entry.retired() == (status == CuratedStatus.RETIRED)) {
            return entry;
        }
        CuratedPracticeOverride override =
                practiceOverrides.findBySlug(slug).orElseGet(() -> new CuratedPracticeOverride(slug, clock.instant()));
        override.setStatus(status, clock.instant());
        if (override.isEmpty()) {
            practiceOverrides.delete(override);
        } else {
            practiceOverrides.save(override);
        }
        return recordPractice(slug, entry);
    }

    @Transactional
    public CatalogEntry<GroupDefinition> writeGroup(
            String slug, @Nullable EntityTagPrecondition precondition, GroupDefinition definition) {
        lockCatalog();
        CatalogEntry<GroupDefinition> entry =
                CuratedCatalogModel.requireEntry(loadCatalog().group(slug), CATALOG_GROUP, slug, precondition);
        if (entry.overridden() != null && definition.equals(entry.shipped())) {
            clearGroupDefinition(slug);
            return recordGroup(slug, entry);
        }
        if (entry.effective().equals(definition)) {
            return entry;
        }
        CuratedGroupOverride override =
                groupOverrides.findBySlug(slug).orElseGet(() -> new CuratedGroupOverride(slug, clock.instant()));
        override.write(definition, CuratedCatalogModel.digestOf(entry.shipped(), slug), clock.instant());
        groupOverrides.save(override);
        return recordGroup(slug, entry);
    }

    @Transactional
    public CatalogEntry<GroupDefinition> createGroup(String slug, GroupDefinition definition) {
        lockCatalog();
        EffectiveCatalog before = loadCatalog();
        if (before.group(slug).isPresent()) {
            throw new CuratedCatalogConflictException("A group with slug '" + slug + "' already exists.");
        }
        Instant now = clock.instant();
        CuratedGroupOverride override = new CuratedGroupOverride(slug, now);
        override.write(definition, null, now);
        groupOverrides.save(override);
        CatalogEntry<GroupDefinition> created =
                loadCatalog().group(slug).orElseThrow(() -> new EntityNotFoundException(CATALOG_GROUP, slug));
        configAudit.record(ConfigAuditEntry.instanceCreated(
                ConfigAuditEntityType.CURATED_PRACTICE_GROUP, created.slug(), CuratedGroupSnapshot.of(created)));
        return created;
    }

    @Transactional
    public CatalogEntry<GroupDefinition> resetGroup(String slug, @Nullable EntityTagPrecondition precondition) {
        lockCatalog();
        CatalogEntry<GroupDefinition> entry =
                CuratedCatalogModel.requireEntry(loadCatalog().group(slug), CATALOG_GROUP, slug, precondition);
        if (entry.shipped() == null) {
            throw new CuratedCatalogConflictException("Hephaestus ships no definition for '" + slug + "'.");
        }
        groupOverrides.findBySlug(slug).ifPresent(override -> {
            override.clearDefinition(clock.instant());
            if (override.isEmpty()) {
                groupOverrides.delete(override);
            } else {
                groupOverrides.save(override);
            }
        });
        return recordGroup(slug, entry);
    }

    @Transactional
    public CatalogEntry<GroupDefinition> keepGroup(String slug, @Nullable EntityTagPrecondition precondition) {
        lockCatalog();
        CatalogEntry<GroupDefinition> entry =
                CuratedCatalogModel.requireEntry(loadCatalog().group(slug), CATALOG_GROUP, slug, precondition);
        if (entry.overridden() == null) {
            return entry;
        }
        groupOverrides.findBySlug(slug).ifPresent(override -> {
            override.acknowledge(CuratedCatalogModel.digestOf(entry.shipped(), slug), clock.instant());
            groupOverrides.save(override);
        });
        return recordGroup(slug, entry);
    }

    @Transactional
    public EffectiveCatalog setGroupStatus(
            String slug, @Nullable EntityTagPrecondition precondition, CuratedStatus status) {
        lockCatalog();
        EffectiveCatalog before = loadCatalog();
        CuratedCatalogModel.requireCatalog(before, precondition);
        CatalogEntry<GroupDefinition> entry =
                before.group(slug).orElseThrow(() -> new EntityNotFoundException(CATALOG_GROUP, slug));
        if (entry.retired() == (status == CuratedStatus.RETIRED)) {
            return before;
        }
        CuratedGroupOverride override =
                groupOverrides.findBySlug(slug).orElseGet(() -> new CuratedGroupOverride(slug, clock.instant()));
        override.setStatus(status, clock.instant());
        if (override.isEmpty()) {
            groupOverrides.delete(override);
        } else {
            groupOverrides.save(override);
        }
        recordGroup(slug, entry);
        return loadCatalog();
    }

    @Transactional
    public EffectiveCatalog reorderGroups(@Nullable EntityTagPrecondition precondition, List<String> orderedSlugs) {
        lockCatalog();
        EffectiveCatalog before = loadCatalog();
        CuratedCatalogModel.requireCatalog(before, precondition);
        CuratedCatalogModel.validateCompleteOrder(
                before.groups().stream().map(CatalogEntry::slug).toList(), orderedSlugs, CATALOG_GROUP);
        if (before.groups().stream().map(CatalogEntry::slug).toList().equals(orderedSlugs)) {
            return before;
        }
        Instant now = clock.instant();
        for (int position = 0; position < orderedSlugs.size(); position++) {
            CuratedGroupOverride override = groupOverride(orderedSlugs.get(position), now);
            override.setPosition(position, now);
            groupOverrides.save(override);
        }
        return loadCatalog();
    }

    @Transactional
    public EffectiveCatalog reorderPractices(
            @Nullable EntityTagPrecondition precondition, @Nullable String groupSlug, List<String> orderedSlugs) {
        lockCatalog();
        EffectiveCatalog before = loadCatalog();
        CuratedCatalogModel.requireCatalog(before, precondition);
        if (groupSlug != null && before.group(groupSlug).isEmpty()) {
            throw new EntityNotFoundException(CATALOG_GROUP, groupSlug);
        }
        List<String> existing = CuratedCatalogModel.practicesIn(before, groupSlug).stream()
                .map(CatalogEntry::slug)
                .toList();
        CuratedCatalogModel.validateCompleteOrder(existing, orderedSlugs, CATALOG_PRACTICE);
        if (existing.equals(orderedSlugs)) {
            return before;
        }
        resequencePractices(orderedSlugs, clock.instant());
        return loadCatalog();
    }

    @Transactional
    public EffectiveCatalog resetOrder(@Nullable EntityTagPrecondition precondition) {
        lockCatalog();
        EffectiveCatalog before = loadCatalog();
        CuratedCatalogModel.requireCatalog(before, precondition);
        if (!before.customOrder()) {
            return before;
        }
        Instant now = clock.instant();
        practiceOverrides.findAll().stream()
                .filter(override -> override.getPosition() != null)
                .forEach(override -> {
                    override.clearPosition(now);
                    if (override.isEmpty()) {
                        practiceOverrides.delete(override);
                    } else {
                        practiceOverrides.save(override);
                    }
                });
        groupOverrides.findAll().stream()
                .filter(override -> override.getPosition() != null)
                .forEach(override -> {
                    override.clearPosition(now);
                    if (override.isEmpty()) {
                        groupOverrides.delete(override);
                    } else {
                        groupOverrides.save(override);
                    }
                });
        return loadCatalog();
    }

    @Transactional
    public EffectiveCatalog placePractice(
            String slug, @Nullable EntityTagPrecondition precondition, @Nullable String groupSlug, int position) {
        lockCatalog();
        EffectiveCatalog before = loadCatalog();
        CuratedCatalogModel.requireCatalog(before, precondition);
        CatalogEntry<PracticeDefinition> entry =
                before.practice(slug).orElseThrow(() -> new EntityNotFoundException(CATALOG_PRACTICE, slug));
        if (groupSlug != null && before.group(groupSlug).isEmpty()) {
            throw new EntityNotFoundException(CATALOG_GROUP, groupSlug);
        }
        String sourceGroupSlug = entry.effective().groupSlug();
        if (Objects.equals(sourceGroupSlug, groupSlug)) {
            throw new IllegalArgumentException("Use the reorder endpoint to move a practice within its group");
        }
        List<String> source = CuratedCatalogModel.practicesIn(before, sourceGroupSlug).stream()
                .map(CatalogEntry::slug)
                .filter(candidate -> !candidate.equals(slug))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<String> target = CuratedCatalogModel.practicesIn(before, groupSlug).stream()
                .map(CatalogEntry::slug)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (position < 0 || position > target.size()) {
            throw new IllegalArgumentException("position exceeds the destination size");
        }
        target.add(position, slug);

        Instant now = clock.instant();
        PracticeDefinition definition = entry.effective();
        PracticeDefinition moved = new PracticeDefinition(
                definition.name(),
                definition.bindings(),
                definition.criteria(),
                definition.precomputeScript(),
                definition.automatedReviewPolicy(),
                definition.whyItMatters(),
                definition.whatGoodLooksLike(),
                groupSlug);
        CuratedPracticeOverride override = practiceOverride(slug, now);
        override.write(moved, CuratedCatalogModel.digestOf(entry.shipped(), slug), now);
        practiceOverrides.save(override);
        resequencePractices(source, now);
        resequencePractices(target, now);
        recordPractice(slug, entry);
        return loadCatalog();
    }

    private CatalogEntry<PracticeDefinition> recordPractice(String slug, CatalogEntry<PracticeDefinition> before) {
        CatalogEntry<PracticeDefinition> after = loadPractice(slug);
        configAudit.record(ConfigAuditEntry.instanceUpdated(
                ConfigAuditEntityType.CURATED_PRACTICE,
                slug,
                CuratedPracticeSnapshot.of(before),
                CuratedPracticeSnapshot.of(after)));
        return after;
    }

    private CatalogEntry<GroupDefinition> recordGroup(String slug, CatalogEntry<GroupDefinition> before) {
        CatalogEntry<GroupDefinition> after =
                loadCatalog().group(slug).orElseThrow(() -> new EntityNotFoundException(CATALOG_GROUP, slug));
        configAudit.record(ConfigAuditEntry.instanceUpdated(
                ConfigAuditEntityType.CURATED_PRACTICE_GROUP,
                slug,
                CuratedGroupSnapshot.of(before),
                CuratedGroupSnapshot.of(after)));
        return after;
    }

    private void lockCatalog() {
        catalogLock.acquire();
    }

    private CuratedPracticeOverride practiceOverride(String slug, Instant now) {
        return practiceOverrides.findBySlug(slug).orElseGet(() -> new CuratedPracticeOverride(slug, now));
    }

    private CuratedGroupOverride groupOverride(String slug, Instant now) {
        return groupOverrides.findBySlug(slug).orElseGet(() -> new CuratedGroupOverride(slug, now));
    }

    private void resequencePractices(List<String> orderedSlugs, Instant now) {
        for (int position = 0; position < orderedSlugs.size(); position++) {
            CuratedPracticeOverride override = practiceOverride(orderedSlugs.get(position), now);
            override.setPosition(position, now);
            practiceOverrides.save(override);
        }
    }

    private void clearPracticeDefinition(String slug) {
        practiceOverrides.findBySlug(slug).ifPresent(override -> {
            override.clearDefinition(clock.instant());
            if (override.isEmpty()) {
                practiceOverrides.delete(override);
            } else {
                practiceOverrides.save(override);
            }
        });
    }

    private void clearGroupDefinition(String slug) {
        groupOverrides.findBySlug(slug).ifPresent(override -> {
            override.clearDefinition(clock.instant());
            if (override.isEmpty()) {
                groupOverrides.delete(override);
            } else {
                groupOverrides.save(override);
            }
        });
    }
}
