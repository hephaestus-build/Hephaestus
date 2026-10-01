package de.tum.cit.aet.hephaestus.practices.curated;

import static de.tum.cit.aet.hephaestus.practices.curated.CuratedCatalogFixtures.group;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

class EffectiveCatalogTest extends BaseUnitTest {

    @Test
    void summaryCountsPracticesWithheldByARetiredGroupAsNotOffered() {
        var groupDefinition = group("Quality", "Make work easy to change");
        var retiredGroup = new CatalogEntry<>("quality", groupDefinition, groupDefinition, null, null, true, 0, null);
        PracticeDefinition definition = new PracticeDefinition(
                "Small PRs",
                PracticeTestEvidence.signals(ArtifactKinds.PULL_REQUEST),
                PracticeTestEvidence.needsFor(ArtifactKinds.PULL_REQUEST),
                false,
                ActorRole.AUTHOR,
                null,
                "Criteria",
                null,
                PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST),
                "Reason",
                null,
                "quality");
        var catalog = new EffectiveCatalog(
                List.of(retiredGroup), List.of(CatalogEntry.shippedOnly("small-prs", definition, 0)));

        assertThat(catalog.summary().notOffered()).isEqualTo(2);
    }

    @Test
    void catalogTagIncludesWhetherTheInstanceOwnsTheOrder() {
        var groupDefinition = group("Quality", "Make work easy to change");
        var groups = List.of(CatalogEntry.shippedOnly("quality", groupDefinition, 0));

        assertThat(new EffectiveCatalog(groups, List.of(), true).etag())
                .isNotEqualTo(new EffectiveCatalog(groups, List.of(), false).etag());
    }

    @Test
    void catalogTagIncludesEntryPositions() {
        var groupDefinition = group("Quality", "Make work easy to change");
        var first = CatalogEntry.shippedOnly("quality", groupDefinition, 0);
        var moved = CatalogEntry.shippedOnly("quality", groupDefinition, 1);

        assertThat(new EffectiveCatalog(List.of(first), List.of()).etag())
                .isNotEqualTo(new EffectiveCatalog(List.of(moved), List.of()).etag());
    }
}
