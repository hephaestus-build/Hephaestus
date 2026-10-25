package de.tum.cit.aet.hephaestus.core.tenancy;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code hephaestus.tenancy.*} configuration properties.
 *
 * <p>Defaults to {@link TenancyEnforcement#THROW}. The {@code LOG} and {@code OFF} modes are
 * explicit diagnostic overrides that weaken isolation enforcement.
 *
 * @param enforcement How the SQL inspector reacts to a query on a workspace-scoped table that has no workspace guard.
 *     The value throw fails the query, log logs a warning and counts it, and off turns inspection off. Use log or off
 *     only for diagnostics.
 */
@ConfigurationProperties(prefix = "hephaestus.tenancy")
public record TenancyEnforcementProperties(TenancyEnforcement enforcement) {
    public TenancyEnforcementProperties {
        if (enforcement == null) {
            enforcement = TenancyEnforcement.THROW;
        }
    }
}
