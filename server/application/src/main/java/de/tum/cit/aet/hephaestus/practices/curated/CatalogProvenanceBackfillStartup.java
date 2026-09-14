package de.tum.cit.aet.hephaestus.practices.curated;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@Profile("!test & !specs")
@ConditionalOnServerRole
@RequiredArgsConstructor
class CatalogProvenanceBackfillStartup implements ApplicationRunner, HealthIndicator {

    private final CatalogProvenanceBackfill backfill;
    private volatile boolean failed;

    @Override
    public void run(ApplicationArguments arguments) {
        try {
            backfill.run();
        } catch (RuntimeException exception) {
            failed = true;
            log.error("Could not run catalog provenance repair", exception);
        }
    }

    @Override
    public Health health() {
        return failed
                ? Health.outOfService()
                        .withDetail("reason", "CATALOG_PROVENANCE_REPAIR_FAILED")
                        .build()
                : Health.up().build();
    }
}
