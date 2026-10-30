package de.tum.cit.aet.hephaestus;

import de.tum.cit.aet.hephaestus.core.database.LegacySecretsChange;
import de.tum.cit.aet.hephaestus.integration.core.connection.migration.LegacyConnectionSecretsChange;
import liquibase.Liquibase;
import liquibase.change.custom.CustomChangeWrapper;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Gives the one-time conversion the same key configuration as the application. */
@Configuration(proxyBeanMethods = false)
public class SecretMigrationConfiguration {
    @Bean
    Customizer<Liquibase> legacySecretKeys(Environment environment) {
        return liquibase -> {
            try {
                for (var changeSet : liquibase.getDatabaseChangeLog().getChangeSets()) {
                    for (var change : changeSet.getChanges()) {
                        if (change instanceof CustomChangeWrapper wrapper
                                && wrapper.getCustomChange() instanceof LegacySecretsChange secrets) {
                            secrets.configure(environment);
                        }
                        if (change instanceof CustomChangeWrapper wrapper
                                && wrapper.getCustomChange() instanceof LegacyConnectionSecretsChange secrets) {
                            secrets.configure(environment);
                        }
                    }
                }
            } catch (LiquibaseException exception) {
                throw new IllegalStateException("Could not configure the secret conversion", exception);
            }
        };
    }
}
