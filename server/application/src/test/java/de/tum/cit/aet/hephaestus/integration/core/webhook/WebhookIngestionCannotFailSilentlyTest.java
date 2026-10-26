package de.tum.cit.aet.hephaestus.integration.core.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.unit.DataSize;

/**
 * The shipped configuration, checked as the deployment sees it.
 *
 * <p>Every assertion here corresponds to a way an ingestion outage went unnoticed or spread: a health signal
 * that took every Pod out of rotation at once, a storage bound below what loss costs, bounds that overcommit
 * the volume, and a receiver admitting payloads the broker will not carry.
 *
 * <p>The readiness assertions drive the shipped {@code management.*} keys through the same
 * autoconfiguration a deployment does. The defect they exist to catch was a property path that binds
 * to nothing, and reading the value back out of the YAML reports that bug as fixed.
 */
class WebhookIngestionCannotFailSilentlyTest extends BaseUnitTest {

    private static final Path NATS_COMPOSE = Path.of("..", "..", "docker", "compose.core.yaml");
    private static final Path SHIPPED_ENV = Path.of("..", "..", "docker", ".env.example");
    private static final Path CHART_VALUES = Path.of("..", "..", "deploy", "chart", "hephaestus", "values.yaml");

    /** GitHub deliveries a busy instance receives in a day. */
    private static final long BUSY_GITHUB_BYTES_PER_DAY = 750_000_000L;

    /**
     * Health contributors of the shipped roles, as bean name to the contributor name Spring derives from it.
     * Always present, on every role: {@code readinessState}.
     */
    private static final Map<String, String> CONTRIBUTOR_BEANS = Map.of(
            "webhookHealthIndicator",
            "webhook",
            "integrationConsumerHealthIndicator",
            "integrationConsumer",
            "practiceReviewHealthIndicator",
            "practiceReview",
            "kubernetesSandboxHealthIndicator",
            "kubernetesSandbox");

    /** What a webhook-only container has: the server role is off, so most of the group is absent. */
    private static final Set<String> WEBHOOK_ROLE_ONLY =
            Set.of("webhookHealthIndicator", "practiceReviewHealthIndicator");

    @Test
    void readinessAsksOnlyWhetherThisPodCanServe() {
        healthContext(CONTRIBUTOR_BEANS.keySet())
                .run(context -> assertThat(
                                context.getBean(HealthEndpointGroups.class).get("readiness"))
                        .as(
                                "the shipped keys must produce a configured readiness group, not the auto-configured probe group")
                        .isNotNull()
                        .satisfies(group -> {
                            // A broker outage unready every Pod at once and leaves no Pod to serve or receive.
                            assertThat(group.isMember("webhook"))
                                    .as("JetStream is a remote dependency")
                                    .isFalse();
                            assertThat(group.isMember("integrationConsumer"))
                                    .as("the NATS connection is a remote dependency")
                                    .isFalse();
                            assertThat(group.isMember("kubernetesSandbox"))
                                    .as("the worker's own isolation proof")
                                    .isTrue();
                            assertThat(group.isMember("practiceReview"))
                                    .as("the worker's own configuration")
                                    .isTrue();
                        }));
    }

    @Test
    void aJetStreamFailureLeavesReadinessUpAndHealthDown() {
        healthContext(
                        CONTRIBUTOR_BEANS.keySet(),
                        Map.of(
                                "webhookHealthIndicator",
                                Health.down()
                                        .withDetail("reason", "JetStream unreachable")
                                        .build()))
                .run(context -> {
                    var endpoint = context.getBean(HealthEndpoint.class);
                    var readiness = endpoint.healthForPath("readiness");
                    assertNotNull(readiness);
                    assertThat(readiness.getStatus()).isEqualTo(Status.UP);
                    // ADR 0008's webhook-server-down alert reads the whole health endpoint, which still knows.
                    assertThat(endpoint.health().getStatus()).isEqualTo(Status.DOWN);
                });
    }

    @Test
    void readinessFormsOnAContainerMissingMostOfItsContributors() {
        healthContext(WEBHOOK_ROLE_ONLY).run(context -> {
            assertThat(context).hasNotFailed();
            var readiness = context.getBean(HealthEndpointGroups.class).get("readiness");
            assertNotNull(readiness);
            assertThat(readiness.isMember("webhook")).isFalse();
        });
    }

    @Test
    void membershipValidationIsWhatTheReadinessGroupIsExemptedFrom() {
        // The control for the test above. Every named contributor is role-gated, so with validation
        // on — its default — the split deployment this branch exists to protect cannot boot at all.
        healthContext(WEBHOOK_ROLE_ONLY)
                .withPropertyValues("management.endpoint.health.validate-group-membership=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void everyNameInTheReadinessGroupIsAContributorThatCanExist() {
        // Turning validation off is what lets a container form the group without its absent
        // contributors; it also means a misspelled name is silently ignored forever. This is that
        // check, moved to where it can run against every role at once instead of one at a time.
        List<String> configured =
                List.of(binder().bind("management.endpoint.health.group.readiness.include", String.class)
                        .get()
                        .split(","));

        assertThat(configured)
                .as("a name no contributor answers to is a member the probe silently never evaluates")
                .isSubsetOf(union(Set.of("readinessState"), CONTRIBUTOR_BEANS.values()));
    }

    @Test
    void theByteBoundOutlastsWhatReconciliationCanRepair() throws IOException {
        WebhookProperties.Stream stream = shippedStream();
        int reconciliationWindowDays = widestShippedReconciliationWindowDays();

        long repairableTraffic = reconciliationWindowDays * BUSY_GITHUB_BYTES_PER_DAY;

        assertThat(stream.maxBytesFor("github"))
                .as(
                        "webhook deliveries are not redeliverable (ADR 0008), but nightly RECONCILIATION "
                                + "re-fetches the last %d days from the provider API, so inside that window a shed "
                                + "message is recoverable by other means and outside it by nothing. This manufactures a "
                                + "coupling the code does not have: raising the reconciliation window without raising "
                                + "the byte bound is meant to fail here rather than in production",
                        reconciliationWindowDays)
                .isGreaterThan(repairableTraffic);
    }

    @Test
    void aBusyGitHubStreamStaysBelowHalfItsByteBound() throws IOException {
        WebhookProperties.Stream stream = shippedStream();
        Duration shippedAge = DurationStyle.detectAndParse(shippedEnvValue("HEPHAESTUS_WEBHOOK_STREAM_MAX_AGE"));
        Long shippedBytes = shippedEnvSizes("HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES_GITHUB")
                .get("HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES_GITHUB");
        assertNotNull(shippedBytes);

        assertThat(busyBytesHeldFor(stream.maxAgeFor("github")))
                .as("consumers acknowledge within seconds, so max-age is what holds the stream in steady state. "
                        + "A stream at its byte bound deletes its oldest message on every publish, so a busy "
                        + "stream has to age out well before it reaches that bound")
                .isLessThanOrEqualTo(stream.maxBytesFor("github") / 2);
        assertThat(busyBytesHeldFor(shippedAge))
                .as("docker/.env.example sets what a deployment following the documented setup runs")
                .isLessThanOrEqualTo(shippedBytes / 2);

        Binder chart = Binder.get(environmentOf(
                new YamlPropertySourceLoader().load("values.yaml", new FileSystemResource(CHART_VALUES))));
        Duration chartAge = DurationStyle.detectAndParse(
                chart.bind("webhook.stream.max-age", String.class).get());
        long chartBytes = DataSize.parse(chart.bind("webhook.stream.max-bytes-github", String.class)
                        .get())
                .toBytes();
        assertThat(busyBytesHeldFor(chartAge))
                .as("the Helm chart's values.yaml sets what a Kubernetes release runs")
                .isLessThanOrEqualTo(chartBytes / 2);
    }

    private static StandardEnvironment environmentOf(List<PropertySource<?>> sources) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.forEach(environment.getPropertySources()::addLast);
        return environment;
    }

    private static long busyBytesHeldFor(Duration age) {
        return BUSY_GITHUB_BYTES_PER_DAY * age.toHours() / 24;
    }

    /**
     * The widest reconciliation window any shipped artefact selects. {@code application.yml} carries the
     * default, {@code docker/.env.example} carries what a deployment following the documented setup
     * actually runs, and the byte bound has to outlast whichever is larger — a bound checked against
     * the default alone is green while the deployment it ships with loses messages.
     */
    private int widestShippedReconciliationWindowDays() throws IOException {
        int fromYaml =
                binder().bind("hephaestus.sync.timeframe-days", Integer.class).get();
        Matcher shipped = Pattern.compile("^MONITORING_TIMEFRAME=(\\d+)$", Pattern.MULTILINE)
                .matcher(Files.readString(SHIPPED_ENV));
        return shipped.find() ? Math.max(fromYaml, Integer.parseInt(shipped.group(1))) : fromYaml;
    }

    @Test
    void theShippedEnvFileFitsInsideTheBudgetItSets() throws IOException {
        Map<String, Long> shipped = shippedEnvSizes(
                "NATS_JS_MAX_FILE_BYTES",
                "HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES",
                "HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES_GITHUB");
        Long perStream = shipped.get("HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES");
        Long github = shipped.get("HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES_GITHUB");
        assertNotNull(perStream);
        assertNotNull(github);
        long total = github + perStream * (WebhookJetStreamBootstrap.STREAMS.length - 1);

        assertThat(total)
                .as("these four numbers are what an operator following docker/.env.example runs, and the "
                        + "receiver refuses to start when the bounds overcommit the budget. The defaults in "
                        + "application.yml agree with each other whether or not this file does")
                .isLessThanOrEqualTo(shipped.get("NATS_JS_MAX_FILE_BYTES"));
    }

    private String shippedEnvValue(String name) throws IOException {
        Matcher m = Pattern.compile("^" + Pattern.quote(name) + "=(\\S+)$", Pattern.MULTILINE)
                .matcher(Files.readString(SHIPPED_ENV));
        assertThat(m.find()).as("%s is set in docker/.env.example", name).isTrue();
        return m.group(1);
    }

    /**
     * Values as an operator gets them from the documented setup, not as application.yml defaults them.
     * Parsed as {@link DataSize}, so the file may say {@code 10GB} or the byte count and this reads
     * the same number either way.
     */
    private Map<String, Long> shippedEnvSizes(String... names) throws IOException {
        String env = Files.readString(SHIPPED_ENV);
        Map<String, Long> values = new LinkedHashMap<>();
        for (String name : names) {
            Matcher m = Pattern.compile("^" + Pattern.quote(name) + "=(\\S+)$", Pattern.MULTILINE)
                    .matcher(env);
            assertThat(m.find()).as("%s is set in docker/.env.example", name).isTrue();
            values.put(name, DataSize.parse(m.group(1)).toBytes());
        }
        return values;
    }

    @Test
    void streamBoundsFitInsideTheBrokerStorageBudget() {
        WebhookProperties.Stream stream = shippedStream();

        long total = 0;
        for (String name : WebhookJetStreamBootstrap.STREAMS) {
            total += stream.maxBytesFor(name);
        }

        assertThat(total)
                .as("JetStream that fills the filesystem cannot write its own metadata and stays wedged")
                .isLessThanOrEqualTo(stream.storageBudget().toBytes());
    }

    @Test
    void theBrokerBudgetAndTheStreamBudgetAreTheSameNumber() throws IOException {
        assertThat(defaultOf("max_file"))
                .as("two numbers for one budget means the deployed one is whichever file the reader did not open")
                .isEqualTo(shippedStream().storageBudget().toBytes());
    }

    @Test
    void theBrokerCarriesEveryPayloadTheReceiverAccepts() throws IOException {
        long accepted = binder().bind("hephaestus.webhook.http.max-payload-bytes", Long.class)
                .get();

        assertThat(defaultOf("max_payload"))
                .as("NATS defaults max_payload to 1MB: a delivery above it is verified, accepted, and then "
                        + "refused at publish, which is loss the receiver has already answered for")
                .isGreaterThanOrEqualTo(accepted);
    }

    /**
     * The shipped {@code management.*} keys driving real health autoconfiguration, with one stub
     * contributor per bean name the given runtime role would have contributed.
     */
    private static ApplicationContextRunner healthContext(Set<String> contributorBeans) {
        return healthContext(contributorBeans, Map.of());
    }

    /** The given contributors, each UP unless {@code reports} names its health. */
    private static ApplicationContextRunner healthContext(Set<String> contributorBeans, Map<String, Health> reports) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        HealthContributorRegistryAutoConfiguration.class, HealthEndpointAutoConfiguration.class))
                .withPropertyValues(shippedPropertyValues("management."))
                .withBean("readinessState", HealthIndicator.class, () -> Health.up()::build);
        for (String bean : contributorBeans) {
            Health health = reports.getOrDefault(bean, Health.up().build());
            runner = runner.withBean(bean, HealthIndicator.class, () -> () -> health);
        }
        return runner;
    }

    /** Every shipped key under {@code prefix}, as the {@code key=value} pairs the runner takes. */
    private static String[] shippedPropertyValues(String prefix) {
        List<String> values = new ArrayList<>();
        for (PropertySource<?> source : shippedPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    if (name.startsWith(prefix)) {
                        values.add(name + "=" + enumerable.getProperty(name));
                    }
                }
            }
        }
        return values.toArray(String[]::new);
    }

    private static List<String> union(Set<String> first, Collection<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static WebhookProperties.Stream shippedStream() {
        return binder().bind("hephaestus.webhook.stream", WebhookProperties.Stream.class)
                .get();
    }

    /** The `${VAR:-default}` a `nats-server.conf` setting falls back to when the operator sets nothing. */
    private static long defaultOf(String setting) throws IOException {
        String text = Files.readString(NATS_COMPOSE);
        Matcher matcher =
                Pattern.compile(setting + ":\\s*\\$\\{[A-Z0-9_]+:-(\\d+)}").matcher(text);
        assertThat(matcher.find()).as("%s is set in %s", setting, NATS_COMPOSE).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    /** Binds the shipped `application.yml` with no environment, so defaults are what a fresh deploy gets. */
    private static Binder binder() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        for (PropertySource<?> source : shippedPropertySources()) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment);
    }

    private static List<PropertySource<?>> shippedPropertySources() {
        try {
            return new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        } catch (IOException e) {
            throw new IllegalStateException("application.yml is not on the test classpath", e);
        }
    }
}
