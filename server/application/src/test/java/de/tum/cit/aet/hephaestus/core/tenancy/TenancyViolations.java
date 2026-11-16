package de.tum.cit.aet.hephaestus.core.tenancy;

import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The statements that the tenancy check refused, as {@code tenancy.violation.total} counts them. A caller can catch the
 * refusal and only log it, so a test reads the count to see a refusal that left no exception.
 */
public final class TenancyViolations {

    private TenancyViolations() {}

    /** Every refusal so far, of any table. */
    public static double count(MeterRegistry registry) {
        return registry.find(CoreMetrics.TENANCY_VIOLATION_TOTAL).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }

    /** The refusals so far of statements that read or wrote the table. */
    public static double count(MeterRegistry registry, String table) {
        return registry.find(CoreMetrics.TENANCY_VIOLATION_TOTAL).tag("table", table).counters().stream()
                .mapToDouble(Counter::count)
                .sum();
    }
}
