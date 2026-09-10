package de.tum.cit.aet.hephaestus.practices.curated;

import static de.tum.cit.aet.hephaestus.practices.curated.CuratedCatalogFixtures.group;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.GroupDefinition;
import de.tum.cit.aet.hephaestus.practices.curated.BundledPracticeCatalog.BundledEntry;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CuratedCatalogModelTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");

    @Test
    void customEntryDoesNotFreezeTheBundledOrder() {
        CuratedGroupOverride custom = customGroup("custom", "Custom");
        BundledPracticeCatalog first = catalog(entry("a", "A", 0), entry("b", "B", 1));
        BundledPracticeCatalog reordered = catalog(entry("b", "B", 0), entry("a", "A", 1));

        assertThat(slugs(CuratedCatalogModel.compose(first, List.of(custom), List.of())))
                .containsExactly("a", "b", "custom");
        assertThat(slugs(CuratedCatalogModel.compose(reordered, List.of(custom), List.of())))
                .containsExactly("b", "a", "custom");
    }

    @Test
    void sameNameCustomEntriesHaveAStableSlugTieBreak() {
        CuratedGroupOverride second = customGroup("z-second", "Same name");
        CuratedGroupOverride first = customGroup("a-first", "Same name");

        assertThat(slugs(CuratedCatalogModel.compose(catalog(), List.of(second, first), List.of())))
                .containsExactly("a-first", "z-second");
    }

    private static CuratedGroupOverride customGroup(String slug, String name) {
        CuratedGroupOverride override = new CuratedGroupOverride(slug, NOW);
        override.write(group(name, "Description"), null, NOW);
        return override;
    }

    private static BundledEntry<GroupDefinition> entry(String slug, String name, int position) {
        return new BundledEntry<>(slug, group(name, "Description"), position);
    }

    @SafeVarargs
    private static BundledPracticeCatalog catalog(BundledEntry<GroupDefinition>... groups) {
        List<BundledEntry<GroupDefinition>> entries = new java.util.ArrayList<>(groups.length);
        for (BundledEntry<GroupDefinition> group : groups) {
            entries.add(group);
        }
        return new BundledPracticeCatalog(entries, List.of());
    }

    private static List<String> slugs(EffectiveCatalog catalog) {
        return catalog.groups().stream().map(CatalogEntry::slug).toList();
    }
}
