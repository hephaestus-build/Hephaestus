package de.tum.cit.aet.hephaestus.practices.catalog;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.CanonicalDigest;
import de.tum.cit.aet.hephaestus.practices.GroupDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionField;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionMerge;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionValidator;
import de.tum.cit.aet.hephaestus.practices.PracticeReleaseChoice;
import de.tum.cit.aet.hephaestus.practices.dto.CatalogGroupInputDTO;
import de.tum.cit.aet.hephaestus.practices.dto.CatalogGroupUpdateDTO;
import de.tum.cit.aet.hephaestus.practices.dto.CatalogPracticeInputDTO;
import de.tum.cit.aet.hephaestus.practices.dto.CatalogPracticeUpdateDTO;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@ConditionalOnServerRole
public class CatalogCustomizationService {
    private final CatalogService catalogService;
    private final CatalogEntryRepository entries;
    private final PracticeDefinitionValidator validator;
    private final CatalogLock lock;
    private final JsonMapper mapper;
    private final ConfigAuditPort audit;

    @Transactional
    public CatalogOffer<CatalogPracticeDocument> createPractice(CatalogPracticeInputDTO input) {
        lock.acquire();
        var catalog = catalogService.effectiveCatalog();
        if (catalog.practice(input.slug()).isPresent())
            throw new CatalogConflictException("This catalog slug already exists.");
        return writePractice(
                catalog,
                input.slug(),
                new CatalogPracticeDocument(
                        new ItemKind(input.content().kind()),
                        input.content().groupSlug(),
                        input.content().definition().toDefinition()),
                input.content().position());
    }

    @Transactional
    public CatalogOffer<CatalogPracticeDocument> updatePractice(
            String slug, CatalogPracticeUpdateDTO input, @Nullable EntityTagPrecondition precondition) {
        lock.acquire();
        var catalog = catalogService.effectiveCatalog();
        EntityTagPrecondition.requireExact(precondition, practice(catalog, slug).etag());
        return writePractice(
                catalog,
                slug,
                new CatalogPracticeDocument(
                        new ItemKind(input.kind()),
                        input.groupSlug(),
                        input.definition().toDefinition()),
                input.position());
    }

    private CatalogOffer<CatalogPracticeDocument> writePractice(
            EffectiveCatalog catalog, String slug, CatalogPracticeDocument document, @Nullable Integer position) {
        var current = catalog.practice(slug);
        ItemKind kind = document.kind();
        if (current.isPresent() && !current.get().effective().kind().equals(kind))
            throw new InvalidInputException("A catalog practice keeps its item kind.");
        validator.validate(kind, document.definition());
        if (document.groupSlug() != null) group(catalog, document.groupSlug());
        var entry = authoredEntry(catalog, CatalogEntryType.PRACTICE, slug, position, document.groupSlug());
        entry.setKind(kind);
        entry.setDefinition(mapper.valueToTree(document.content()));
        entries.saveAndFlush(entry);
        var result = practice(catalogService.effectiveCatalog(), slug);
        record(CatalogEntryType.PRACTICE, slug, current.map(this::snapshot).orElse(null), snapshot(result));
        return result;
    }

    @Transactional
    public CatalogOffer<GroupDefinition> createGroup(CatalogGroupInputDTO input) {
        lock.acquire();
        var catalog = catalogService.effectiveCatalog();
        if (catalog.group(input.slug()).isPresent())
            throw new CatalogConflictException("This catalog slug already exists.");
        return writeGroup(
                catalog,
                input.slug(),
                new GroupDefinition(
                        input.content().name(),
                        input.content().description(),
                        input.content().icon(),
                        input.content().color()),
                input.content().position());
    }

    @Transactional
    public CatalogOffer<GroupDefinition> updateGroup(
            String slug, CatalogGroupUpdateDTO input, @Nullable EntityTagPrecondition precondition) {
        lock.acquire();
        var catalog = catalogService.effectiveCatalog();
        EntityTagPrecondition.requireExact(precondition, group(catalog, slug).etag());
        return writeGroup(
                catalog,
                slug,
                new GroupDefinition(input.name(), input.description(), input.icon(), input.color()),
                input.position());
    }

    private CatalogOffer<GroupDefinition> writeGroup(
            EffectiveCatalog catalog, String slug, GroupDefinition definition, @Nullable Integer position) {
        var current = catalog.group(slug);
        var entry = authoredEntry(catalog, CatalogEntryType.GROUP, slug, position, null);
        entry.setDefinition(mapper.valueToTree(definition));
        entries.saveAndFlush(entry);
        var result = group(catalogService.effectiveCatalog(), slug);
        record(CatalogEntryType.GROUP, slug, current.map(this::snapshot).orElse(null), snapshot(result));
        return result;
    }

    private CatalogEntry authoredEntry(
            EffectiveCatalog catalog,
            CatalogEntryType type,
            String slug,
            @Nullable Integer position,
            @Nullable String groupSlug) {
        CatalogEntry entry = row(type, slug);
        var current = type == CatalogEntryType.PRACTICE ? catalog.practice(slug) : catalog.group(slug);
        if (entry.getBase() == null) {
            Object shipped = current.isEmpty() ? null : current.get().shipped();
            entry.setBase(shipped == null ? null : mapper.valueToTree(shipped));
        }
        Optional<Integer> previousPosition = type == CatalogEntryType.PRACTICE
                ? catalog.practice(slug)
                        .filter(offer -> Objects.equals(offer.effective().groupSlug(), groupSlug))
                        .map(CatalogOffer::position)
                : catalog.group(slug).map(CatalogOffer::position);
        entry.setPosition(
                position == null ? previousPosition.orElseGet(() -> nextPosition(catalog, type, groupSlug)) : position);
        return entry;
    }

    @Transactional
    public void setExcluded(
            CatalogEntryType type, String slug, boolean excluded, @Nullable EntityTagPrecondition precondition) {
        lock.acquire();
        var catalog = catalogService.effectiveCatalog();
        var current = offer(catalog, type, slug);
        EntityTagPrecondition.requireExact(precondition, current.etag());
        CatalogSnapshot before = snapshot(current);
        CatalogEntry entry = row(type, slug);
        if (excluded != (entry.getExcludedAt() != null)) {
            entry.setExcludedAt(excluded ? Instant.now() : null);
            entries.saveAndFlush(entry);
            record(type, slug, before, snapshot(offer(catalogService.effectiveCatalog(), type, slug)));
        }
    }

    @Transactional
    public void reset(CatalogEntryType type, String slug, @Nullable EntityTagPrecondition precondition) {
        lock.acquire();
        var catalog = catalogService.effectiveCatalog();
        var current = offer(catalog, type, slug);
        EntityTagPrecondition.requireExact(precondition, current.etag());
        CatalogSnapshot before = snapshot(current);
        CatalogEntry entry = row(type, slug);
        if (current.shipped() == null) {
            throw new InvalidInputException("This entry has no shipped definition to restore.");
        }
        if (type == CatalogEntryType.PRACTICE) {
            var practice = practice(catalog, slug);
            var shipped = Objects.requireNonNull(practice.shipped());
            if (!practice.effective().kind().equals(shipped.kind()))
                throw new CatalogConflictException("A catalog reset cannot change the practice's item kind.");
        }
        entry.setKind(null);
        entry.setDefinition(null);
        entry.setBase(null);
        entries.saveAndFlush(entry);
        record(type, slug, before, snapshot(offer(catalogService.effectiveCatalog(), type, slug)));
    }

    @Transactional
    public void acknowledge(CatalogEntryType type, String slug, @Nullable EntityTagPrecondition precondition) {
        lock.acquire();
        var catalog = catalogService.effectiveCatalog();
        var current = offer(catalog, type, slug);
        EntityTagPrecondition.requireExact(precondition, current.etag());
        CatalogSnapshot before = snapshot(current);
        CatalogEntry entry = row(type, slug);
        Object shipped = current.shipped();
        if (shipped == null) throw new InvalidInputException("This entry has no shipped definition.");
        if (entry.getDefinition() != null) {
            entry.setBase(mapper.valueToTree(shipped));
            entries.saveAndFlush(entry);
            record(type, slug, before, snapshot(offer(catalogService.effectiveCatalog(), type, slug)));
        }
    }

    @Transactional
    public void reorderGroups(List<String> slugs, @Nullable EntityTagPrecondition precondition) {
        lock.acquire();
        var catalog = catalogService.effectiveCatalog();
        EntityTagPrecondition.requireExact(precondition, catalog.etag());
        var existing = catalog.groups().stream().map(CatalogOffer::slug).toList();
        var requested = Set.copyOf(slugs);
        if (slugs.size() != requested.size() || !requested.equals(Set.copyOf(existing))) {
            throw new InvalidInputException("List every catalog group once in the order.");
        }
        for (int position = 0; position < slugs.size(); position++) {
            CatalogEntry entry = row(CatalogEntryType.GROUP, slugs.get(position));
            entry.setPosition(position);
            entries.save(entry);
        }
        entries.flush();
    }

    @Transactional
    public void resetOrder(@Nullable EntityTagPrecondition precondition) {
        lock.acquire();
        EntityTagPrecondition.requireExact(
                precondition, catalogService.effectiveCatalog().etag());
        entries.clearPositions(Instant.now());
    }

    private CatalogEntry row(CatalogEntryType type, String slug) {
        return entries.findById(new CatalogEntry.Id(type, slug)).orElseGet(() -> new CatalogEntry(type, slug));
    }

    private static CatalogOffer<?> offer(EffectiveCatalog catalog, CatalogEntryType type, String slug) {
        return type == CatalogEntryType.PRACTICE ? practice(catalog, slug) : group(catalog, slug);
    }

    private static CatalogOffer<CatalogPracticeDocument> practice(EffectiveCatalog catalog, String slug) {
        return catalog.practice(slug).orElseThrow(() -> new EntityNotFoundException("Catalog practice", slug));
    }

    private static CatalogOffer<GroupDefinition> group(EffectiveCatalog catalog, String slug) {
        return catalog.group(slug).orElseThrow(() -> new EntityNotFoundException("Catalog group", slug));
    }

    @Transactional
    public CatalogOffer<CatalogPracticeDocument> applyRelease(
            String slug,
            Map<PracticeDefinitionField, PracticeReleaseChoice> choices,
            @Nullable EntityTagPrecondition precondition) {
        lock.acquire();
        var catalog = catalogService.effectiveCatalog();
        var current = practice(catalog, slug);
        EntityTagPrecondition.requireExact(precondition, current.etag());
        var shipped = current.shipped();
        if (shipped == null) throw new EntityNotFoundException("Catalog practice release", slug);
        var definition = PracticeDefinitionMerge.apply(
                current.base() == null ? null : current.base().definition(),
                current.effective().definition(),
                shipped.definition(),
                choices);
        validator.validate(current.effective().kind(), definition);
        var before = snapshot(current);
        var entry = row(CatalogEntryType.PRACTICE, slug);
        entry.setKind(current.effective().kind());
        entry.setDefinition(mapper.valueToTree(new CatalogPracticeDocument(
                        current.effective().kind(), current.effective().groupSlug(), definition)
                .content()));
        entry.setBase(mapper.valueToTree(shipped));
        entries.saveAndFlush(entry);
        var result = catalogService.practice(slug);
        record(CatalogEntryType.PRACTICE, slug, before, snapshot(result));
        return result;
    }

    @Transactional
    public void move(
            String slug, @Nullable String groupSlug, int position, @Nullable EntityTagPrecondition precondition) {
        lock.acquire();
        var catalog = catalogService.effectiveCatalog();
        EntityTagPrecondition.requireExact(precondition, catalog.etag());
        var current = practice(catalog, slug);
        var before = snapshot(current);
        if (groupSlug != null) group(catalog, groupSlug);
        CatalogEntry entry = row(CatalogEntryType.PRACTICE, slug);
        var document = current.effective();
        entry.setKind(document.kind());
        entry.setDefinition(mapper.valueToTree(
                new CatalogPracticeDocument(document.kind(), groupSlug, document.definition()).content()));
        if (entry.getBase() == null)
            entry.setBase(current.shipped() == null ? null : mapper.valueToTree(current.shipped()));
        entries.saveAndFlush(entry);
        var changed = catalogService.effectiveCatalog();
        var peers = new ArrayList<>(changed.practices().stream()
                .filter(offer -> !offer.slug().equals(slug)
                        && Objects.equals(offer.effective().groupSlug(), groupSlug))
                .toList());
        peers.add(Math.min(position, peers.size()), practice(changed, slug));
        resequence(peers);
        if (!Objects.equals(document.groupSlug(), groupSlug)) {
            resequence(changed.practices().stream()
                    .filter(offer -> Objects.equals(offer.effective().groupSlug(), document.groupSlug()))
                    .toList());
        }
        entries.flush();
        record(CatalogEntryType.PRACTICE, slug, before, snapshot(catalogService.practice(slug)));
    }

    private void resequence(List<CatalogOffer<CatalogPracticeDocument>> offers) {
        for (int i = 0; i < offers.size(); i++) {
            var entry = row(CatalogEntryType.PRACTICE, offers.get(i).slug());
            entry.setPosition(i);
            entries.save(entry);
        }
    }

    private static int nextPosition(EffectiveCatalog catalog, CatalogEntryType type, @Nullable String groupSlug) {
        List<? extends CatalogOffer<?>> entries = type == CatalogEntryType.PRACTICE
                ? catalog.practices().stream()
                        .filter(offer -> Objects.equals(offer.effective().groupSlug(), groupSlug))
                        .toList()
                : catalog.groups();
        return entries.stream().mapToInt(CatalogOffer::position).max().orElse(-1) + 1;
    }

    private String digest(Object definition) {
        return new CanonicalDigest().add(mapper.writeValueAsString(definition)).hex();
    }

    private CatalogSnapshot snapshot(CatalogOffer<?> offer) {
        return new CatalogSnapshot(
                offer.slug(),
                digest(offer.effective()),
                offer.base() == null ? null : digest(offer.base()),
                offer.position(),
                offer.excluded());
    }

    private void record(CatalogEntryType type, String slug, @Nullable CatalogSnapshot before, CatalogSnapshot after) {
        var entity = type == CatalogEntryType.PRACTICE
                ? ConfigAuditEntityType.CATALOG_PRACTICE
                : ConfigAuditEntityType.CATALOG_GROUP;
        audit.record(
                before == null
                        ? ConfigAuditEntry.instanceCreated(entity, slug, after)
                        : ConfigAuditEntry.instanceUpdated(entity, slug, before, after));
    }
}
