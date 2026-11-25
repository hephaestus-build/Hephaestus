package de.tum.cit.aet.hephaestus.core.authority;

import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;

/**
 * Why a boundary refused work whose authority narrowed after it was requested.
 *
 * @param boundary where the refusal happened
 * @param reason which authority narrowed
 */
public record AuthorityRefusal(AuthorityBoundary boundary, Reason reason) {

    /** The authority that narrowed. */
    public enum Reason {
        /**
         * The workspace's authority narrowed, for example by a membership change, after the work was requested. The
         * work does not go on under the old authority.
         */
        WORKSPACE_AUTHORITY_NARROWED,
        /**
         * A person the work depends on narrowed their own choice, such as turning AI off, after the work was requested.
         */
        ACCOUNT_AUTHORITY_NARROWED
    }

    /** Counts this refusal. */
    public void count(MeterRegistry meters) {
        counter(meters, boundary, reason).increment();
    }

    /** Registers every series at 0, so an alert on a refusal that has not happened yet reads one. */
    public static void register(MeterRegistry meters) {
        for (AuthorityBoundary boundary : AuthorityBoundary.values())
            for (Reason reason : Reason.values()) counter(meters, boundary, reason);
    }

    private static Counter counter(MeterRegistry meters, AuthorityBoundary boundary, Reason reason) {
        return Counter.builder(CoreMetrics.AUTHORITY_REFUSED)
                .description("Work refused at a boundary because its authority narrowed, by boundary and reason")
                .tag("boundary", boundary.name().toLowerCase(Locale.ROOT))
                .tag("reason", reason.name().toLowerCase(Locale.ROOT))
                .register(meters);
    }
}
