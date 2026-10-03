package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

/**
 * Tests for {@link NatsConsumerProperties}. Verifies both the pure-constructor defaults
 * (used by every collaborator that builds the bean directly in tests) and the Spring
 * binding path (used at runtime via {@code @ConfigurationPropertiesScan}).
 */
class NatsConsumerPropertiesTest extends BaseUnitTest {

    @Nested
    class ConstructorDefaults {

        @Test
        void nullPoisonBlockUsesDefaults() throws ReflectiveOperationException {
            NatsConsumerProperties props = NatsConsumerProperties.class
                    .getDeclaredConstructor(
                            Duration.class,
                            int.class,
                            Duration.class,
                            Duration.class,
                            NatsConsumerProperties.PoisonProperties.class)
                    .newInstance(Duration.ofMinutes(5), 500, Duration.ofSeconds(2), Duration.ofDays(30), null);

            assertThat(props.poison()).isNotNull();
            assertThat(props.poison().maxRedeliver()).isEqualTo(10);
            assertThat(props.poison().baseDelay()).isEqualTo(Duration.ofSeconds(2));
            assertThat(props.poison().maxDelay()).isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        void explicitPoisonBlockKept() {
            NatsConsumerProperties.PoisonProperties custom =
                    new NatsConsumerProperties.PoisonProperties(3, Duration.ofSeconds(5), Duration.ofMinutes(1));
            NatsConsumerProperties props = new NatsConsumerProperties(
                    Duration.ofMinutes(5), 500, Duration.ofSeconds(2), Duration.ofDays(30), custom);

            assertThat(props.poison()).isSameAs(custom);
        }
    }

    @Nested
    class SpringBinding {

        @EnableConfigurationProperties(NatsConsumerProperties.class)
        static class TestConfiguration {}

        private ApplicationContextRunner runner() {
            return new ApplicationContextRunner()
                    .withUserConfiguration(TestConfiguration.class, ValidationAutoConfiguration.class);
        }

        @Test
        @DisplayName("binds defaults when no properties are set")
        void emptyConfigYieldsDefaults() {
            runner().run(context -> {
                assertThat(context).hasNotFailed();
                NatsConsumerProperties props = context.getBean(NatsConsumerProperties.class);

                assertThat(props.ackWait()).isEqualTo(Duration.ofMinutes(5));
                assertThat(props.maxAckPending()).isEqualTo(500);
                assertThat(props.reconnectDelay()).isEqualTo(Duration.ofSeconds(2));
                assertThat(props.poison()).isNotNull();
                assertThat(props.poison().maxRedeliver()).isEqualTo(10);
                assertThat(props.poison().baseDelay()).isEqualTo(Duration.ofSeconds(2));
                assertThat(props.poison().maxDelay()).isEqualTo(Duration.ofMinutes(5));
            });
        }

        @Test
        void explicitOverridesBound() {
            runner().withPropertyValues(
                            "hephaestus.integration.consumer.ack-wait=10m",
                            "hephaestus.integration.consumer.max-ack-pending=1000",
                            "hephaestus.integration.consumer.poison.max-redeliver=5",
                            "hephaestus.integration.consumer.poison.base-delay=4s",
                            "hephaestus.integration.consumer.poison.max-delay=1m")
                    .run(context -> {
                        NatsConsumerProperties props = context.getBean(NatsConsumerProperties.class);
                        assertThat(props.ackWait()).isEqualTo(Duration.ofMinutes(10));
                        assertThat(props.maxAckPending()).isEqualTo(1000);
                        assertThat(props.poison().maxRedeliver()).isEqualTo(5);
                        assertThat(props.poison().baseDelay()).isEqualTo(Duration.ofSeconds(4));
                        assertThat(props.poison().maxDelay()).isEqualTo(Duration.ofMinutes(1));
                    });
        }

        @Test
        void shouldBoundDurableLifetimeWhenInactiveThresholdIsUnset() {
            // The default is what a deployment that never configures this gets, and it is the only
            // thing that ever reaps a durable.
            runner().run(context -> assertThat(
                            context.getBean(NatsConsumerProperties.class).inactiveThreshold())
                    .isEqualTo(Duration.ofDays(30)));
        }

        @Test
        void shouldDisableReapingOnlyWhenAskedExplicitly() {
            runner().withPropertyValues("hephaestus.integration.consumer.inactive-threshold=0s")
                    .run(context -> assertThat(context.getBean(NatsConsumerProperties.class)
                                    .inactiveThreshold())
                            .isZero());
        }

        @Test
        void shouldRejectAThresholdShortEnoughToReapAcrossARestart() {
            // 30m expires the durable during a slow deploy, and its replacement starts at
            // DeliverPolicy.New — losing everything that arrived meanwhile.
            runner().withPropertyValues("hephaestus.integration.consumer.inactive-threshold=30m")
                    .run(context -> assertThat(context).hasFailed());
        }

        @Test
        void shouldAcceptAThresholdAtTheFloor() {
            runner().withPropertyValues("hephaestus.integration.consumer.inactive-threshold=1h")
                    .run(context -> assertThat(context.getBean(NatsConsumerProperties.class)
                                    .inactiveThreshold())
                            .isEqualTo(Duration.ofHours(1)));
        }

        @Test
        void shouldReadBareInactiveThresholdAsHours() {
            runner().withPropertyValues("hephaestus.integration.consumer.inactive-threshold=72")
                    .run(context -> assertThat(context.getBean(NatsConsumerProperties.class)
                                    .inactiveThreshold())
                            .isEqualTo(Duration.ofDays(3)));
        }

        @Test
        void shouldRejectNegativeInactiveThreshold() {
            runner().withPropertyValues("hephaestus.integration.consumer.inactive-threshold=-1h")
                    .run(context -> assertThat(context).hasFailed());
        }
    }
}
