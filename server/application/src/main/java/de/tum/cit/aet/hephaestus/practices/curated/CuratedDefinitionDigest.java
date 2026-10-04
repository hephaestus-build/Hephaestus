package de.tum.cit.aet.hephaestus.practices.curated;

import de.tum.cit.aet.hephaestus.practices.CatalogDefinition;
import de.tum.cit.aet.hephaestus.practices.GroupDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;

final class CuratedDefinitionDigest {

    private static final String GROUP_V1 = "group:v1:";
    private static final String PRACTICE = "practice:v4:";

    private CuratedDefinitionDigest() {}

    static String of(String slug, CatalogDefinition definition) {
        String prefix =
                switch (definition) {
                    case GroupDefinition ignored -> GROUP_V1;
                    case PracticeDefinition ignored -> PRACTICE;
                    default ->
                        throw new IllegalArgumentException("Unsupported catalog definition: " + definition.getClass());
                };
        return prefix + definition.digest(slug);
    }
}
