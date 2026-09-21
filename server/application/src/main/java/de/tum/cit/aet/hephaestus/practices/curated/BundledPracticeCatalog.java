package de.tum.cit.aet.hephaestus.practices.curated;

import de.tum.cit.aet.hephaestus.practices.GroupDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import java.util.List;
import org.jspecify.annotations.Nullable;

record BundledPracticeCatalog(
        List<BundledEntry<GroupDefinition>> groups, List<BundledEntry<PracticeDefinition>> practices) {
    BundledPracticeCatalog {
        groups = List.copyOf(groups);
        practices = List.copyOf(practices);
    }

    /**
     * @param holdsAs the one-sentence phrase a bundled practice reads as when it holds; null for a group.
     *     Kept beside the definition rather than in it: the phrase is copy for one surface, so it is not
     *     part of what a workspace copies, customizes or compares against the catalog.
     */
    record BundledEntry<D>(
            String slug,
            D definition,
            int position,
            @Nullable String holdsAs) {}
}
