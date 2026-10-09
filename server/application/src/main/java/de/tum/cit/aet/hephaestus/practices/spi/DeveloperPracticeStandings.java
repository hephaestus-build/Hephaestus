package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** One developer's own practice standings, as their Practice profile reads them, for another module to show them. */
public interface DeveloperPracticeStandings {

    /** Every practice the developer's Practice profile lists, in no particular order. */
    List<Standing> standings(long workspaceId, long developerId);

    /**
     * One practice as the Practice profile reads it.
     *
     * @param standing the profile's words for it, also when there is no verdict
     * @param verdict whether {@code standing} is a claim about the work rather than a reason none could be made
     * @param decidedWork how many pieces of work decided it; fewer than three make it an early read
     * @param direction the direction of its recent evidence, or {@code null} when none is read
     * @param holdsAs the catalog's sentence for a strength that is holding, else {@code null}
     */
    record Standing(
            String slug,
            String name,
            @Nullable String group,
            String standing,
            boolean verdict,
            int decidedWork,
            @Nullable String direction,
            int toWorkOn,
            int strengths,
            @Nullable String holdsAs) {}
}
