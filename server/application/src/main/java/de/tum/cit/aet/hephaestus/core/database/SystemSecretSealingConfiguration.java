package de.tum.cit.aet.hephaestus.core.database;

import liquibase.Liquibase;
import liquibase.change.custom.CustomChangeWrapper;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Gives {@link SystemSecretSealingChange} the key configuration of the application, in the migrate run mode and
 * wherever else Liquibase runs.
 */
@Configuration(proxyBeanMethods = false)
public class SystemSecretSealingConfiguration {

    @Bean
    Customizer<Liquibase> systemSecretSealingKeys(Environment environment) {
        return liquibase -> {
            try {
                for (var changeSet : liquibase.getDatabaseChangeLog().getChangeSets()) {
                    for (var change : changeSet.getChanges()) {
                        if (change instanceof CustomChangeWrapper wrapper
                                && wrapper.getCustomChange() instanceof SystemSecretSealingChange sealing) {
                            sealing.configure(environment);
                        }
                    }
                }
            } catch (LiquibaseException exception) {
                throw new IllegalStateException("Could not configure the system secret sealing", exception);
            }
        };
    }
}
