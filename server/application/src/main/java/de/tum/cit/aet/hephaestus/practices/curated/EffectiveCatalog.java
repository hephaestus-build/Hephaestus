package de.tum.cit.aet.hephaestus.practices.curated;

import de.tum.cit.aet.hephaestus.practices.CanonicalDigest;
import de.tum.cit.aet.hephaestus.practices.GroupDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public record EffectiveCatalog(
        List<CatalogEntry<GroupDefinition>> groups,
        List<CatalogEntry<PracticeDefinition>> practices,
        boolean customOrder) {
    public EffectiveCatalog {
        groups = List.copyOf(groups);
        practices = List.copyOf(practices);
    }

    public EffectiveCatalog(
            List<CatalogEntry<GroupDefinition>> groups, List<CatalogEntry<PracticeDefinition>> practices) {
        this(groups, practices, false);
    }

    public Optional<CatalogEntry<PracticeDefinition>> practice(String slug) {
        return practices.stream().filter(entry -> entry.slug().equals(slug)).findFirst();
    }

    public Optional<CatalogEntry<GroupDefinition>> group(String slug) {
        return groups.stream().filter(entry -> entry.slug().equals(slug)).findFirst();
    }

    public String etag() {
        CanonicalDigest digest =
                new CanonicalDigest().addInt(customOrder ? 1 : 0).addInt(groups.size());
        groups.forEach(entry -> digest.add(entry.etag()).addInt(entry.position()));
        digest.addInt(practices.size());
        practices.forEach(entry -> digest.add(entry.etag()).addInt(entry.position()));
        return digest.hex();
    }

    /** Returns entries included in a new workspace, respecting excluded parent groups. */
    public List<CatalogEntry<PracticeDefinition>> installablePractices() {
        return practices.stream().filter(this::isEffectivelyOffered).toList();
    }

    public boolean isEffectivelyOffered(CatalogEntry<PracticeDefinition> practice) {
        if (!practice.offered()) {
            return false;
        }
        String groupSlug = practice.effective().groupSlug();
        return groupSlug == null || group(groupSlug).map(CatalogEntry::offered).orElse(false);
    }

    public List<CatalogEntry<GroupDefinition>> installableGroups() {
        return groups.stream().filter(CatalogEntry::offered).toList();
    }

    public List<String> offeredPracticesIn(String groupSlug) {
        return practices.stream()
                .filter(CatalogEntry::offered)
                .filter(entry -> groupSlug.equals(entry.effective().groupSlug()))
                .map(CatalogEntry::slug)
                .toList();
    }

    public CatalogSummary summary() {
        int total = practices.size() + groups.size();
        int notOffered =
                total - installableGroups().size() - installablePractices().size();
        return new CatalogSummary(
                total,
                count(CatalogEntryState.UPDATE_WAITING, CatalogChangeKind.REVIEW),
                count(CatalogEntryState.UPDATE_WAITING, CatalogChangeKind.WORDING),
                count(CatalogEntryState.UPDATE_WAITING, CatalogChangeKind.PRESENTATION),
                (int) entries()
                        .filter(entry -> entry.state() == CatalogEntryState.EDITED_HERE)
                        .count(),
                (int) entries()
                        .filter(entry -> entry.state() == CatalogEntryState.YOURS)
                        .count(),
                notOffered,
                (int) entries()
                        .filter(entry -> entry.state() == CatalogEntryState.NO_LONGER_SHIPPED)
                        .count());
    }

    private int count(CatalogEntryState state, CatalogChangeKind kind) {
        return (int) entries()
                .filter(entry -> entry.state() == state && entry.changeKind() == kind)
                .count();
    }

    private Stream<CatalogEntry<?>> entries() {
        return Stream.concat(groups.stream(), practices.stream());
    }

    public record CatalogSummary(
            int total,
            int updatesChangingReview,
            int updatesChangingWordingOnly,
            int updatesChangingPresentation,
            int editedHere,
            int yours,
            int notOffered,
            int noLongerShipped) {
        public int updatesWaiting() {
            return updatesChangingReview + updatesChangingWordingOnly + updatesChangingPresentation;
        }
    }
}
