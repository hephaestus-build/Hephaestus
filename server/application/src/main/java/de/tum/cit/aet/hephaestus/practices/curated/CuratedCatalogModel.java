package de.tum.cit.aet.hephaestus.practices.curated;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.practices.CatalogDefinition;
import de.tum.cit.aet.hephaestus.practices.GroupDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.curated.BundledPracticeCatalog.BundledEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

final class CuratedCatalogModel {

    private static final String CATALOG = "Practice catalog";
    private static final String CATALOG_GROUP = "Catalog group";

    private CuratedCatalogModel() {}

    static EffectiveCatalog compose(
            BundledPracticeCatalog bundled,
            List<CuratedGroupOverride> groupOverrides,
            List<CuratedPracticeOverride> practiceOverrides) {
        return new EffectiveCatalog(
                orderGroups(
                        composeEntries(
                                bundled.groups(),
                                groupOverrides,
                                CuratedGroupOverride::getSlug,
                                CuratedGroupOverride::definition,
                                CuratedGroupOverride::getAcceptedBundledDigest,
                                CuratedGroupOverride::getRetiredAt,
                                CuratedGroupOverride::getPosition,
                                CuratedGroupOverride::getUpdatedAt),
                        positionedSlugs(
                                groupOverrides, CuratedGroupOverride::getSlug, CuratedGroupOverride::getPosition)),
                orderPractices(
                        withdrawnAsShipped(composeEntries(
                                bundled.practices(),
                                practiceOverrides,
                                CuratedPracticeOverride::getSlug,
                                CuratedPracticeOverride::definition,
                                CuratedPracticeOverride::getAcceptedBundledDigest,
                                CuratedPracticeOverride::getRetiredAt,
                                CuratedPracticeOverride::getPosition,
                                CuratedPracticeOverride::getUpdatedAt)),
                        positionedSlugs(
                                practiceOverrides,
                                CuratedPracticeOverride::getSlug,
                                CuratedPracticeOverride::getPosition)),
                groupOverrides.stream().anyMatch(override -> override.getPosition() != null)
                        || practiceOverrides.stream().anyMatch(override -> override.getPosition() != null));
    }

    /**
     * A shipped withdrawal from automated review is not a default an instance customization can keep out:
     * the entry offers what adoption and review will enforce. The stored customization is left as written.
     */
    private static List<CatalogEntry<PracticeDefinition>> withdrawnAsShipped(
            List<CatalogEntry<PracticeDefinition>> entries) {
        return entries.stream()
                .map(entry -> {
                    PracticeDefinition shipped = entry.shipped();
                    if (shipped == null || shipped.automatedReviewPolicy().insufficiencyReason() == null) {
                        return entry;
                    }
                    return new CatalogEntry<>(
                            entry.slug(),
                            entry.effective().withdrawnAs(shipped),
                            shipped,
                            entry.overridden(),
                            entry.acceptedBundledDigest(),
                            entry.retired(),
                            entry.position(),
                            entry.updatedAt());
                })
                .toList();
    }

    static List<CatalogEntry<PracticeDefinition>> practicesIn(EffectiveCatalog catalog, @Nullable String groupSlug) {
        return catalog.practices().stream()
                .filter(entry -> Objects.equals(entry.effective().groupSlug(), groupSlug))
                .toList();
    }

    static void requireCatalog(EffectiveCatalog catalog, @Nullable EntityTagPrecondition precondition) {
        if (precondition == null) {
            throw new CuratedPreconditionRequiredException();
        }
        if (!precondition.matches(catalog.etag())) {
            throw new StaleCuratedEntryException(CATALOG);
        }
    }

    static void validateCompleteOrder(List<String> existing, List<String> requested, String type) {
        if (new HashSet<>(requested).size() != requested.size()) {
            throw new IllegalArgumentException("orderedSlugs must not contain duplicate slugs");
        }
        Set<String> existingSlugs = new HashSet<>(existing);
        Set<String> requestedSlugs = new HashSet<>(requested);
        if (existingSlugs.equals(requestedSlugs)) {
            return;
        }
        String unknown = requestedSlugs.stream()
                .filter(slug -> !existingSlugs.contains(slug))
                .findFirst()
                .orElse(null);
        if (unknown != null) {
            throw new EntityNotFoundException(type, unknown);
        }
        throw new IllegalArgumentException("orderedSlugs must contain every entry in the list");
    }

    static void validatePracticeGroup(EffectiveCatalog catalog, PracticeDefinition definition) {
        if (definition.groupSlug() != null
                && catalog.group(definition.groupSlug()).isEmpty()) {
            throw new EntityNotFoundException(CATALOG_GROUP, definition.groupSlug());
        }
    }

    static <D extends CatalogDefinition> CatalogEntry<D> requireEntry(
            java.util.Optional<CatalogEntry<D>> entry,
            String type,
            String slug,
            @Nullable EntityTagPrecondition precondition) {
        CatalogEntry<D> found = entry.orElseThrow(() -> new EntityNotFoundException(type, slug));
        if (precondition == null) {
            throw new CuratedPreconditionRequiredException();
        }
        if (!precondition.matches(found.etag())) {
            throw new StaleCuratedEntryException(type + " '" + slug + "'");
        }
        return found;
    }

    static @Nullable String digestOf(@Nullable CatalogDefinition definition, String slug) {
        return definition == null ? null : CuratedDefinitionDigest.of(slug, definition);
    }

    static List<CatalogEntry<GroupDefinition>> orderGroups(
            List<CatalogEntry<GroupDefinition>> entries, Set<String> positionedSlugs) {
        boolean locallyOrdered = entries.stream().anyMatch(entry -> positionedSlugs.contains(entry.slug()));
        Comparator<CatalogEntry<GroupDefinition>> order = Comparator.comparingInt(
                        (CatalogEntry<GroupDefinition> entry) ->
                                locallyOrdered && !positionedSlugs.contains(entry.slug()) ? 1 : 0)
                .thenComparingInt(CatalogEntry::position)
                .thenComparing(entry -> entry.effective().name())
                .thenComparing(CatalogEntry::slug);
        List<CatalogEntry<GroupDefinition>> sorted =
                entries.stream().sorted(order).toList();
        List<CatalogEntry<GroupDefinition>> result = new ArrayList<>(sorted.size());
        for (int position = 0; position < sorted.size(); position++) {
            result.add(withPosition(sorted.get(position), position));
        }
        return List.copyOf(result);
    }

    static List<CatalogEntry<PracticeDefinition>> orderPractices(
            List<CatalogEntry<PracticeDefinition>> entries, Set<String> positionedSlugs) {
        Set<@Nullable String> locallyOrderedGroups = new HashSet<>();
        entries.stream()
                .filter(entry -> positionedSlugs.contains(entry.slug()))
                .map(entry -> entry.effective().groupSlug())
                .forEach(locallyOrderedGroups::add);
        Comparator<CatalogEntry<PracticeDefinition>> order = Comparator.comparing(
                        (CatalogEntry<PracticeDefinition> entry) ->
                                entry.effective().groupSlug(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(
                        entry -> locallyOrderedGroups.contains(entry.effective().groupSlug())
                                        && !positionedSlugs.contains(entry.slug())
                                ? 1
                                : 0)
                .thenComparingInt(CatalogEntry::position)
                .thenComparing(entry -> entry.effective().name())
                .thenComparing(CatalogEntry::slug);
        List<CatalogEntry<PracticeDefinition>> sorted =
                entries.stream().sorted(order).toList();
        List<CatalogEntry<PracticeDefinition>> result = new ArrayList<>(sorted.size());
        @Nullable String previousGroup = null;
        int position = 0;
        boolean first = true;
        for (CatalogEntry<PracticeDefinition> entry : sorted) {
            String group = entry.effective().groupSlug();
            if (first || !Objects.equals(previousGroup, group)) {
                position = 0;
                previousGroup = group;
                first = false;
            } else {
                position++;
            }
            result.add(withPosition(entry, position));
        }
        return List.copyOf(result);
    }

    private static <O> Set<String> positionedSlugs(
            List<O> overrides, Function<O, String> slugOf, Function<O, @Nullable Integer> positionOf) {
        return overrides.stream()
                .filter(override -> positionOf.apply(override) != null)
                .map(slugOf)
                .collect(Collectors.toSet());
    }

    private static <D extends CatalogDefinition> CatalogEntry<D> withPosition(CatalogEntry<D> entry, int position) {
        return new CatalogEntry<>(
                entry.slug(),
                entry.effective(),
                entry.shipped(),
                entry.overridden(),
                entry.acceptedBundledDigest(),
                entry.retired(),
                position,
                entry.updatedAt());
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private static <D extends CatalogDefinition, O> List<CatalogEntry<D>> composeEntries(
            List<BundledEntry<D>> shipped,
            List<O> overrides,
            Function<O, String> slugOf,
            Function<O, @Nullable D> definitionOf,
            Function<O, @Nullable String> acceptedBundledDigestOf,
            Function<O, @Nullable Instant> retiredAtOf,
            Function<O, @Nullable Integer> positionOf,
            Function<O, Instant> updatedAtOf) {
        Map<String, O> bySlug = overrides.stream().collect(Collectors.toMap(slugOf, Function.identity()));
        Set<String> slugs =
                new LinkedHashSet<>(shipped.stream().map(BundledEntry::slug).toList());
        slugs.addAll(bySlug.keySet());
        Map<String, D> shippedBySlug =
                shipped.stream().collect(Collectors.toMap(BundledEntry::slug, BundledEntry::definition));
        Map<String, Integer> shippedPositionBySlug =
                shipped.stream().collect(Collectors.toMap(BundledEntry::slug, BundledEntry::position));

        List<CatalogEntry<D>> entries = new ArrayList<>();
        for (String slug : slugs) {
            D shippedDefinition = shippedBySlug.get(slug);
            O override = bySlug.get(slug);
            if (override == null) {
                entries.add(CatalogEntry.shippedOnly(
                        slug,
                        Objects.requireNonNull(shippedDefinition),
                        Objects.requireNonNull(shippedPositionBySlug.get(slug))));
                continue;
            }
            D overridden = definitionOf.apply(override);
            D effective = overridden != null ? overridden : shippedDefinition;
            if (effective == null) {
                continue;
            }
            Integer overridePosition = positionOf.apply(override);
            entries.add(new CatalogEntry<>(
                    slug,
                    effective,
                    shippedDefinition,
                    overridden,
                    acceptedBundledDigestOf.apply(override),
                    retiredAtOf.apply(override) != null,
                    overridePosition != null
                            ? overridePosition
                            : shippedPositionBySlug.getOrDefault(slug, Integer.MAX_VALUE),
                    updatedAtOf.apply(override)));
        }
        return entries;
    }
}
