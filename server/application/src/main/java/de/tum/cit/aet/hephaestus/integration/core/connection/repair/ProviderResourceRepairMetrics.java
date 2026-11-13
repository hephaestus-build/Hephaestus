package de.tum.cit.aet.hephaestus.integration.core.connection.repair;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** One aggregate read per scrape window; no per-resource identifiers enter a metric label. */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Counts unresolved security identities by provider and state, without owner or resource labels")
public final class ProviderResourceRepairMetrics {
    private final ProviderResourceRepairRepository repairs;
    private final Cache<String, Map<String, Long>> samples = Caffeine.newBuilder()
            .maximumSize(1)
            .expireAfterWrite(Duration.ofSeconds(10))
            .build();

    public ProviderResourceRepairMetrics(ProviderResourceRepairRepository repairs, MeterRegistry meters) {
        this.repairs = repairs;
        for (IntegrationKind kind : IntegrationKind.values()) {
            for (ProviderResourceRepair.State state : ProviderResourceRepair.State.values()) {
                Gauge.builder(
                                IntegrationCoreMetrics.INTEGRATION_PROVIDER_REPAIR_OPEN,
                                this,
                                owner -> owner.open(kind, state))
                        .description("Provider resources whose removal or registration is unresolved")
                        .tag("kind", kind.name().toLowerCase(Locale.ROOT))
                        .tag("state", state.name().toLowerCase(Locale.ROOT))
                        .register(meters);
            }
        }
    }

    private double open(IntegrationKind kind, ProviderResourceRepair.State state) {
        Map<String, Long> sample = samples.get("open", ignored -> {
            Map<String, Long> values = new HashMap<>();
            for (var count : repairs.countOpenByKindAndState())
                values.put(key(count.getKind(), count.getState()), count.getAmount());
            return values;
        });
        return sample.getOrDefault(key(kind, state), 0L).doubleValue();
    }

    private static String key(IntegrationKind kind, ProviderResourceRepair.State state) {
        return kind.name() + "/" + state.name();
    }
}
