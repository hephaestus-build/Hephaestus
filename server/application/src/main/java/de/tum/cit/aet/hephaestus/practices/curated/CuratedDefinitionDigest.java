package de.tum.cit.aet.hephaestus.practices.curated;

import de.tum.cit.aet.hephaestus.practices.CatalogDefinition;
import de.tum.cit.aet.hephaestus.practices.GroupDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDeliveryBehavior;

final class CuratedDefinitionDigest {

    private static final String GROUP_V1 = "group:v1:";
    private static final String PRACTICE_V2 = "practice:v2:";

    private CuratedDefinitionDigest() {}

    static String of(String slug, CatalogDefinition definition) {
        String prefix =
                switch (definition) {
                    case GroupDefinition ignored -> GROUP_V1;
                    case PracticeDefinition ignored -> PRACTICE_V2;
                    default ->
                        throw new IllegalArgumentException("Unsupported catalog definition: " + definition.getClass());
                };
        return prefix + definition.digest(slug);
    }

    static String beforeDeliveryBehavior(String slug, PracticeDefinition definition) {
        return of(
                slug,
                new PracticeDefinition(
                        definition.name(),
                        definition.bindings(),
                        definition.criteria(),
                        definition.precomputeScript(),
                        definition.automatedReviewPolicy(),
                        definition.whyItMatters(),
                        definition.whatGoodLooksLike(),
                        definition.groupSlug(),
                        PracticeDeliveryBehavior.DEFAULT));
    }
}
