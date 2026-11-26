package de.tum.cit.aet.hephaestus.agent.composer;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a work place may read of one sealed review: the public results, the parts of the work they cite, the work's own
 * captured record and the catalog entries of their practices. Only {@link WorkPlaceScope} builds one, so the scope alone
 * decides what the public writer reads.
 *
 * @param files the selected files by path
 * @param results the public admitted results, in the shape and order the composers read them
 * @param notReached the review's practices with no public result
 * @param onWorkCopyForms the copy forms the work takes
 */
public record WorkPlaceHandoff(
        Map<String, byte[]> files, List<ObjectNode> results, List<String> notReached, List<String> onWorkCopyForms) {
    public WorkPlaceHandoff {
        files = Collections.unmodifiableMap(new TreeMap<>(files));
        results = List.copyOf(results);
        notReached = List.copyOf(notReached);
        onWorkCopyForms = List.copyOf(onWorkCopyForms);
    }
}
