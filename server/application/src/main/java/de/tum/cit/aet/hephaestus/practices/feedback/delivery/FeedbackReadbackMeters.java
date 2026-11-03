package de.tum.cit.aet.hephaestus.practices.feedback.delivery;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationFamily;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.LookupPresence;
import de.tum.cit.aet.hephaestus.practices.metrics.PracticeMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Counts each readback of a copy whose write was in doubt, by provider and by what the provider showed. A copy that its
 * write confirmed is never read back, so its {@code readback} column stays empty and this counter does not see it.
 */
@Component
@ConditionalOnServerRole
public class FeedbackReadbackMeters {

    private final MeterRegistry registry;

    /**
     * Registers every provider and outcome at zero. A counter that first appears at 1 has no earlier sample, so
     * {@code increase()} would miss the first readback of each outcome after each start.
     */
    public FeedbackReadbackMeters(MeterRegistry registry) {
        this.registry = registry;
        for (IntegrationKind provider : IntegrationKind.values()) {
            if (provider.family() != IntegrationFamily.SCM) continue;
            for (LookupPresence outcome : LookupPresence.values()) counter(provider, outcome);
        }
    }

    void record(IntegrationKind provider, LookupPresence outcome) {
        counter(provider, outcome).increment();
    }

    /**
     * What the provider showed for one copy, as {@link FeedbackSendTransactions} records it: a copy that the lookup did
     * not return, or returned as never written, is absent.
     */
    static LookupPresence outcome(List<CopyReceipt> found, UUID copyId) {
        var receipt = found.stream()
                .filter(candidate -> candidate.copyId().equals(copyId))
                .findFirst()
                .orElse(null);
        if (receipt == null || Boolean.FALSE.equals(receipt.writeMayHaveStarted())) return LookupPresence.ABSENT;
        return receipt.acknowledged() ? LookupPresence.FOUND : LookupPresence.UNKNOWN;
    }

    private Counter counter(IntegrationKind provider, LookupPresence outcome) {
        return registry.counter(
                PracticeMetrics.FEEDBACK_READBACK,
                "provider",
                provider.name().toLowerCase(Locale.ROOT),
                "outcome",
                outcome.name().toLowerCase(Locale.ROOT));
    }
}
