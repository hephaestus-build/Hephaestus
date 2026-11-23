package de.tum.cit.aet.hephaestus.architecture;

import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;

import java.util.List;
import java.util.Set;

/**
 * The three domain modules of the conversation agent, lowest first (ADR 0138). Each may depend on the ones before it.
 * Only the runtime and the notification transports sit above all three, so no other module may depend on them.
 */
final class MentoringModules {
    static final List<String> LAYERS = List.of("relationship", "learning", "mentoring");

    /** The packages of the three modules, as ArchUnit package patterns. */
    static final String[] PACKAGES =
            LAYERS.stream().map(module -> BASE_PACKAGE + "." + module + "..").toArray(String[]::new);

    private static final Set<String> ABOVE = Set.of("agent", "notification");

    private MentoringModules() {}

    static boolean contains(String module) {
        return LAYERS.contains(module);
    }

    /** Whether a package belongs to a mentoring module. */
    static boolean holds(String packageName) {
        return LAYERS.stream()
                .map(module -> BASE_PACKAGE + "." + module)
                .anyMatch(root -> packageName.equals(root) || packageName.startsWith(root + "."));
    }

    /** Whether an edge between two modules touches a mentoring module, where the rules of ADR 0138 apply in full. */
    static boolean touches(String source, String target) {
        return contains(source) || contains(target);
    }

    /**
     * Whether {@code source} may depend on {@code target} by the layer order. A mentoring target is constrained, and a
     * mentoring module never depends on the runtime that hosts its runs.
     */
    static boolean mayDependOn(String source, String target) {
        if (contains(source) && ABOVE.contains(target)) return false;
        if (!contains(target) || source.equals(target) || ABOVE.contains(source)) return true;
        return contains(source) && LAYERS.indexOf(source) > LAYERS.indexOf(target);
    }
}
