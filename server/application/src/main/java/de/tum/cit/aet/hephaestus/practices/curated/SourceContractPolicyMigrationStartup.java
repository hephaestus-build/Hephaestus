package de.tum.cit.aet.hephaestus.practices.curated;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test & !specs & !cds-training")
@ConditionalOnServerRole
@RequiredArgsConstructor
class SourceContractPolicyMigrationStartup implements SmartInitializingSingleton {
    private final SourceContractPolicyMigration migration;

    // Runs before context refresh starts scheduled tasks and worker lifecycle listeners.
    @Override
    public void afterSingletonsInstantiated() {
        migration.run();
    }
}
