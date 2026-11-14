package de.tum.cit.aet.hephaestus.integration.core.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubWebhookRedelivery;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.SimpleBeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.AvailabilityProbesAutoConfiguration;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.stereotype.Component;

/**
 * The shipped configuration, checked as the deployment sees it.
 *
 * <p>A health contributor reports a dependency, such as the provider event consumer. When it fails, every server
 * shares that failure, so readiness that read it would take every server out of rotation at once, and no server would
 * be left to answer a provider's delivery with the 503 that makes the provider send it again.
 *
 * <p>The contributors are the application's own {@link HealthIndicator} components, found on the classpath, so a new
 * one is checked without an edit here. The readiness assertions drive the shipped {@code management.*} keys through
 * the same autoconfiguration a deployment does. The defect they exist to catch was a property path that binds to
 * nothing, and reading the value back out of the YAML reports that bug as fixed.
 */
class WebhookIngestionCannotFailSilentlyTest extends BaseUnitTest {

    /** The application's health contributors, as bean name to the contributor name Spring derives from it. */
    private static final Map<String, String> CONTRIBUTOR_BEANS = applicationHealthContributors();

    @Test
    void shouldFindTheApplicationsHealthContributorsWhenItScansTheClasspath() {
        // An empty scan would let every assertion below pass on nothing.
        assertThat(CONTRIBUTOR_BEANS)
                .as("the inbox consumer's contributor, whose failure must not unready a receiver")
                .containsEntry("integrationConsumerHealthIndicator", "integrationConsumer");
    }

    @Test
    void readinessAsksOnlyWhetherThisPodCanServe() {
        // A database or provider outage would unready every Pod at once and leave no Pod to serve or receive.
        healthContext(CONTRIBUTOR_BEANS.keySet())
                .run(context -> assertThat(
                                context.getBean(HealthEndpointGroups.class).get("readiness"))
                        .as(
                                "the shipped keys must produce a configured readiness group, not the auto-configured probe group")
                        .isNotNull()
                        .satisfies(group -> {
                            assertThat(group.isMember("readinessState")).isTrue();
                            assertThat(CONTRIBUTOR_BEANS.values())
                                    .as("no contributor of any role belongs in readiness")
                                    .noneMatch(group::isMember);
                        }));
    }

    @Test
    void livenessAsksOnlyWhetherTheProcessRuns() {
        // A dependency in liveness turns a slow database into a restart loop that drops the deliveries in flight.
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ApplicationAvailabilityAutoConfiguration.class,
                        HealthContributorRegistryAutoConfiguration.class,
                        HealthEndpointAutoConfiguration.class,
                        AvailabilityProbesAutoConfiguration.class))
                .withPropertyValues(shippedPropertyValues("management."));
        for (String bean : CONTRIBUTOR_BEANS.keySet()) {
            runner = runner.withBean(
                    bean, HealthIndicator.class, () -> () -> Health.up().build());
        }
        runner.run(context -> assertThat(
                        context.getBean(HealthEndpointGroups.class).get("liveness"))
                .isNotNull()
                .satisfies(group -> {
                    assertThat(group.isMember("livenessState")).isTrue();
                    assertThat(CONTRIBUTOR_BEANS.values())
                            .as("no contributor of any role belongs in liveness")
                            .noneMatch(group::isMember);
                }));
    }

    @ParameterizedTest
    @MethodSource("contributorBeans")
    void aFailingContributorLeavesReadinessUpAndHealthDown(String contributor) {
        healthContext(
                        CONTRIBUTOR_BEANS.keySet(),
                        Map.of(
                                contributor,
                                Health.down().withDetail("reason", "down").build()))
                .run(context -> {
                    var endpoint = context.getBean(HealthEndpoint.class);
                    var readiness = endpoint.healthForPath("readiness");
                    assertNotNull(readiness);
                    assertThat(readiness.getStatus()).isEqualTo(Status.UP);
                    // The whole health endpoint still reports the failure to an operator.
                    assertThat(endpoint.health().getStatus()).isEqualTo(Status.DOWN);
                });
    }

    @Test
    void theReplayWindowOutlastsTheGitHubRedeliveryLookBack() {
        // A delivery stored after GitHub stopped waiting failed there; the job redelivers it within its window.
        assertThat(binder().bind("hephaestus.integration.consumer.replay-window", Duration.class)
                        .get())
                .as("a redelivery inside the window must find its stored event, not be stored again")
                .isGreaterThan(GitHubWebhookRedelivery.WINDOW);
    }

    static Stream<String> contributorBeans() {
        return CONTRIBUTOR_BEANS.keySet().stream();
    }

    /**
     * The {@link HealthIndicator} components under the application's package. Spring names a contributor after its
     * bean, without the {@code HealthIndicator} suffix.
     */
    private static Map<String, String> applicationHealthContributors() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        TypeFilter indicator = new AssignableTypeFilter(HealthIndicator.class);
        TypeFilter component = new AnnotationTypeFilter(Component.class);
        scanner.addIncludeFilter(
                (reader, factory) -> indicator.match(reader, factory) && component.match(reader, factory));
        var names = new AnnotationBeanNameGenerator();
        var registry = new SimpleBeanDefinitionRegistry();
        Map<String, String> contributors = new TreeMap<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("de.tum.cit.aet.hephaestus")) {
            String bean = names.generateBeanName(candidate, registry);
            contributors.put(bean, bean.replaceFirst("HealthIndicator$", ""));
        }
        return contributors;
    }

    /** The shipped {@code management.*} keys driving real health autoconfiguration, with one stub per contributor. */
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
