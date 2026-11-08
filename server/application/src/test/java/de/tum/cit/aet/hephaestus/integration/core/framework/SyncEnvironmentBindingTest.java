package de.tum.cit.aet.hephaestus.integration.core.framework;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

/** Bind the operator's names through the actual production YAML, including nested backfill settings. */
@Tag("unit")
class SyncEnvironmentBindingTest {
    @Test
    void shouldBindTheSyncEnvironmentNames() throws IOException {
        var properties = bind(Map.of(
                "SYNC_RUN_ON_STARTUP", "false",
                "SYNC_TIMEFRAME_DAYS", "14",
                "SYNC_CRON", "0 0 9 * * *",
                "SYNC_COOLDOWN_MINUTES", "15",
                "SYNC_BACKFILL_ENABLED", "false",
                "SYNC_BACKFILL_BATCH_SIZE", "7",
                "SYNC_BACKFILL_RATE_LIMIT_THRESHOLD", "999",
                "SYNC_BACKFILL_INTERVAL_SECONDS", "300"));
        assertThat(properties)
                .isEqualTo(new SyncSchedulerProperties(
                        false,
                        14,
                        "0 0 9 * * *",
                        15,
                        new SyncSchedulerProperties.BackfillProperties(false, 7, 999, 300),
                        null));
    }

    @Test
    void shouldNotReadTheRetiredMonitoringNames() throws IOException {
        var properties = bind(Map.of(
                "MONITORING_RUN_ON_STARTUP", "false",
                "MONITORING_TIMEFRAME", "14",
                "MONITORING_SYNC_CRON", "0 0 9 * * *",
                "MONITORING_SYNC_COOLDOWN_IN_MINUTES", "15",
                "MONITORING_BACKFILL_ENABLED", "false",
                "MONITORING_BACKFILL_BATCH_SIZE", "7",
                "MONITORING_BACKFILL_RATE_LIMIT_THRESHOLD", "999",
                "MONITORING_BACKFILL_INTERVAL_SECONDS", "300"));
        assertThat(properties)
                .isEqualTo(new SyncSchedulerProperties(
                        true,
                        7,
                        "0 0 * * * *",
                        60,
                        new SyncSchedulerProperties.BackfillProperties(true, 50, 500, 60),
                        null));
    }

    private static SyncSchedulerProperties bind(Map<String, Object> environment) throws IOException {
        var sources = new MutablePropertySources();
        sources.addFirst(new SystemEnvironmentPropertySource("test-env", environment));
        new YamlPropertySourceLoader()
                .load("production", new ClassPathResource("application-prod.yml"))
                .forEach(sources::addLast);
        var binder = new Binder(
                ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
        return binder.bindOrCreate("hephaestus.sync", Bindable.of(SyncSchedulerProperties.class));
    }
}
