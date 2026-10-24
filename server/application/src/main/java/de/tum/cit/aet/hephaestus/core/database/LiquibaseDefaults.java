package de.tum.cit.aet.hephaestus.core.database;

import java.util.Map;
import liquibase.analytics.configuration.AnalyticsArgs;
import liquibase.configuration.AbstractMapConfigurationValueProvider;

/**
 * Liquibase defaults of the application. Their precedence is below every source an operator sets, so an
 * environment variable or system property such as {@code LIQUIBASE_ANALYTICS_ENABLED=true} still wins. Without
 * this default, a release build of Liquibase fetches {@code config.liquibase.com} and sends product usage data on
 * each migration.
 */
public class LiquibaseDefaults extends AbstractMapConfigurationValueProvider {

    private static final Map<String, String> DEFAULTS = Map.of(AnalyticsArgs.ENABLED.getKey(), "false");

    @Override
    public int getPrecedence() {
        return 1;
    }

    @Override
    protected Map<?, ?> getMap() {
        return DEFAULTS;
    }

    @Override
    protected String getSourceDescription() {
        return "Hephaestus defaults";
    }
}
