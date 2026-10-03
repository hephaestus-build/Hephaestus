package de.tum.cit.aet.hephaestus.core.audit;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Computes {@code config_audit_event.changed_keys} — the dot-paths whose value differs between two
 * snapshots.
 *
 * <h2>What counts as a key</h2>
 * A <em>leaf</em> is any node that is not a JSON object: a scalar, an explicit null, or an array
 * (arrays compare whole — element-wise paths would make the {@code changedKey} filter's value space
 * depend on list indices, which are not stable across reorders). A <em>key</em> is the dot-joined
 * field path from the snapshot root to a leaf, e.g. {@code volumeCaps.perPullRequest}. Nested
 * snapshots therefore yield the leaf path, not the containing object, so the per-control filter can
 * address an individual control inside a composite entity.
 *
 * <h2>Create and delete</h2>
 * A CREATED row's keys are every leaf of the new state, and a DELETED row's every leaf of the old —
 * so those rows still match a per-control filter. Only UPDATED can legitimately produce an empty
 * result, which is what {@code ConfigAuditRecorder} treats as a no-op.
 */
final class ConfigAuditDiff {

    private ConfigAuditDiff() {}

    static List<String> changedKeys(@Nullable JsonNode before, @Nullable JsonNode after) {
        if (before == null) {
            return leafPaths(after);
        }
        if (after == null) {
            return leafPaths(before);
        }
        Map<String, JsonNode> beforeLeaves = leaves(before);
        Map<String, JsonNode> afterLeaves = leaves(after);
        Set<String> allPaths = new LinkedHashSet<>(beforeLeaves.keySet());
        allPaths.addAll(afterLeaves.keySet());
        List<String> changed = new ArrayList<>();
        for (String path : allPaths) {
            // A path present on one side only is a change: snapshot shapes evolve, and a field that
            // appeared or vanished is exactly what a reader needs to see.
            if (!Objects.equals(beforeLeaves.get(path), afterLeaves.get(path))) {
                changed.add(path);
            }
        }
        return List.copyOf(new TreeSet<>(changed));
    }

    private static List<String> leafPaths(@Nullable JsonNode node) {
        return List.copyOf(new TreeSet<>(leaves(node).keySet()));
    }

    private static Map<String, JsonNode> leaves(@Nullable JsonNode node) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        collect("", node, out);
        return out;
    }

    private static void collect(String prefix, @Nullable JsonNode node, Map<String, JsonNode> out) {
        if (node == null) {
            return;
        }
        if (node.isObject() && !node.isEmpty()) {
            node.properties()
                    .forEach(
                            e -> collect(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), out));
            return;
        }
        if (!prefix.isEmpty()) {
            out.put(prefix, node);
        }
    }
}
