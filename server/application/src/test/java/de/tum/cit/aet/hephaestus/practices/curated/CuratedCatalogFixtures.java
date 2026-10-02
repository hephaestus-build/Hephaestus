package de.tum.cit.aet.hephaestus.practices.curated;

import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.practices.GroupDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import java.util.Map;

final class CuratedCatalogFixtures {

    private CuratedCatalogFixtures() {}

    static PracticeDefinition practice(String name, String criteria, String whyItMatters) {
        return new PracticeDefinition(
                name,
                PracticeTestEvidence.signals(ArtifactKinds.PULL_REQUEST),
                PracticeTestEvidence.needsFor(ArtifactKinds.PULL_REQUEST),
                Map.of(),
                ActorRole.AUTHOR,
                null,
                criteria,
                null,
                PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST),
                whyItMatters,
                "Bundled exemplar",
                "packaging");
    }

    static GroupDefinition group(String name, String description) {
        return new GroupDefinition(name, description, "Target", "sky");
    }
}
