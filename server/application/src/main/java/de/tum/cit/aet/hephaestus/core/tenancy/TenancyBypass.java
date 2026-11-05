package de.tum.cit.aet.hephaestus.core.tenancy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-local bypass flag for {@link WorkspaceStatementInspector}. When the depth is
 * &gt; 0, the inspector treats emitted SQL as exempt from {@code workspace_id} enforcement.
 *
 * <p>Open via {@link #open(String)} and a try-with-resources guards the matching close, so
 * the depth is always decremented — even on exception. Depth-counted to make nested
 * {@code @WorkspaceAgnostic} calls safe.
 */
public final class TenancyBypass {

    private static final Logger log = LoggerFactory.getLogger(TenancyBypass.class);

    private static final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);

    private TenancyBypass() {}

    /** Open a bypass scope on the current thread. The returned scope MUST be closed. */
    public static Scope open(String reason) {
        int next = depth.get() + 1;
        depth.set(next);
        if (log.isTraceEnabled()) {
            log.trace("Tenancy bypass opened (depth={}): {}", next, reason);
        }
        return () -> {
            int current = depth.get();
            if (current <= 1) {
                depth.remove();
            } else {
                depth.set(current - 1);
            }
        };
    }

    public static boolean isActive() {
        return depth.get() > 0;
    }

    /** AutoCloseable specialization that does not declare a checked exception. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
