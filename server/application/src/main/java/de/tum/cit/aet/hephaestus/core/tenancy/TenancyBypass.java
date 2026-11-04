package de.tum.cit.aet.hephaestus.core.tenancy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-local bypass for {@link WorkspaceStatementInspector}. While a scope is open, the inspector does not enforce
 * the {@code workspace_id} predicate.
 *
 * <p>Open via {@link #open(String)} or {@link #openForType(String, Class)}, and close with try-with-resources so the
 * scope always ends, even on an exception. Scopes nest. A scope that a type-level {@code @WorkspaceAgnostic} opened
 * remembers its type: the inspector still reports what it would have refused there, so that type-level markers can be
 * replaced by method-level ones (ADR 0077).
 */
public final class TenancyBypass {

    private static final Logger log = LoggerFactory.getLogger(TenancyBypass.class);

    /** One entry per open scope: the simple name of the type whose marker opened it, or empty for a method marker. */
    private static final ThreadLocal<Deque<String>> scopes = ThreadLocal.withInitial(ArrayDeque::new);

    private TenancyBypass() {}

    /** Open a bypass scope for a method-level marker. The returned scope MUST be closed. */
    public static Scope open(String reason) {
        return push(reason, "");
    }

    /** Open a bypass scope for a type-level marker on {@code type}. The returned scope MUST be closed. */
    public static Scope openForType(String reason, Class<?> type) {
        return push(reason, type.getSimpleName());
    }

    private static Scope push(String reason, String type) {
        Deque<String> open = scopes.get();
        open.push(type);
        if (log.isTraceEnabled()) {
            log.trace("Tenancy bypass opened (depth={}, type={}): {}", open.size(), type, reason);
        }
        return () -> {
            Deque<String> current = scopes.get();
            current.pop();
            if (current.isEmpty()) scopes.remove();
        };
    }

    public static boolean isActive() {
        return !scopes.get().isEmpty();
    }

    /**
     * The types whose type-level markers alone keep the current statement unchecked, outermost first. Empty when no
     * scope is open, or when a method-level marker is open: that one names the statement's reason itself.
     */
    public static Set<String> silencingTypes() {
        Deque<String> open = scopes.get();
        if (open.isEmpty() || open.contains("")) return Set.of();
        Set<String> types = new LinkedHashSet<>();
        open.descendingIterator().forEachRemaining(types::add);
        return types;
    }

    /** AutoCloseable specialization that does not declare a checked exception. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
