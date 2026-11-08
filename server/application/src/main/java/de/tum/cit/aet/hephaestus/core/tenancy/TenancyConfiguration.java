package de.tum.cit.aet.hephaestus.core.tenancy;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.hibernate.cfg.AvailableSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires SQL-layer tenancy: the {@link WorkspaceStatementInspector} is registered with
 * Hibernate via {@link HibernatePropertiesCustomizer}, and the {@link TenancyViolationReporter}
 * encapsulates the "log vs throw vs off" decision plus Micrometer reporting.
 */
@Configuration
@EnableConfigurationProperties(TenancyEnforcementProperties.class)
public class TenancyConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TenancyConfiguration.class);

    @Bean
    Counter tenancyParseFailureCounter(MeterRegistry registry) {
        return Counter.builder(CoreMetrics.TENANCY_PARSE_FAILURE_TOTAL)
                .description("SQL statements WorkspaceStatementInspector could not parse — fail-open.")
                .tag("module", "tenancy")
                .register(registry);
    }

    @Bean
    TenancyViolationReporter tenancyViolationReporter(MeterRegistry registry) {
        return (sql, unguardedTables, mode) -> {
            for (String table : unguardedTables) {
                registry.counter(
                                CoreMetrics.TENANCY_VIOLATION_TOTAL,
                                "module",
                                "tenancy",
                                "table",
                                table,
                                "mode",
                                mode.name().toLowerCase(Locale.ROOT))
                        .increment();
            }
            log.warn(
                    "Tenancy violation ({}): scoped tables {} queried without workspace_id predicate. SQL: {}",
                    mode.name().toLowerCase(Locale.ROOT),
                    unguardedTables,
                    LoggingUtils.truncate(sql, 200));
            if (mode == TenancyEnforcement.THROW) {
                throw new TenancyViolationException(unguardedTables);
            }
        };
    }

    @Bean
    WorkspaceStatementInspector workspaceStatementInspector(
            WorkspaceScopedTables scopedTables,
            TenancyEnforcementProperties properties,
            TenancyViolationReporter reporter,
            Counter tenancyParseFailureCounter) {
        log.info("Workspace tenancy enforcement mode: {}", properties.enforcement());
        return new WorkspaceStatementInspector(
                scopedTables, properties.enforcement(), reporter, tenancyParseFailureCounter);
    }

    @Bean
    HibernatePropertiesCustomizer tenancyInspectorHibernateCustomizer(WorkspaceStatementInspector inspector) {
        return properties -> properties.put(AvailableSettings.STATEMENT_INSPECTOR, inspector);
    }
}
