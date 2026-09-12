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

/** Brings the installed practice catalog up to what this release ships, once, as the server starts. */
@Component
@Slf4j
@Profile("!test & !specs & !cds-training")
@ConditionalOnServerRole
@RequiredArgsConstructor
class CatalogProvenanceBackfillStartup implements ApplicationRunner, HealthIndicator {

    private final SourceContractPolicyMigration policyMigration;
    private final CatalogProvenanceBackfill backfill;
    private volatile boolean failed;

    @Override
    public void run(ApplicationArguments arguments) {
        // Not caught, unlike the repair below: an installed policy this release cannot upgrade aborts the
        // boot rather than leaving a server up that reviews against a contract it no longer ships.
        policyMigration.run();
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
