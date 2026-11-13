package de.tum.cit.aet.hephaestus.agent.attempt;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnBackgroundWork;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration(proxyBeanMethods = false)
@ConditionalOnAgentRuntime
public class AttemptTokenConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AttemptTokenConfiguration.class);

    /** How long a published key takes at most to reach the other servers. */
    private static final Duration REFRESH = Duration.ofSeconds(5);

    /** Far below {@link AttemptKeyring#KEPT_FOR}, so that a live server's key never expires. */
    private static final Duration PUBLISH = Duration.ofMinutes(1);

    @Bean
    AttemptSigningKey attemptSigningKey() {
        return AttemptSigningKey.generate();
    }

    @Bean
    AttemptKeyStore attemptKeyStore(AttemptPublicKeyRepository keys) {
        return new AttemptKeyStore() {
            @Override
            public void publish(String kid, byte[] publicKey, Duration keptFor) {
                keys.publish(kid, publicKey, keptFor.toSeconds());
            }

            @Override
            public Map<String, byte[]> live() {
                Map<String, byte[]> live = new HashMap<>();
                for (AttemptPublicKey key : keys.findLive()) live.put(key.getKid(), key.getPublicKey());
                return live;
            }

            @Override
            public void removeExpired() {
                keys.deleteExpired();
            }
        };
    }

    @Bean
    AttemptKeyring attemptKeyring(AttemptSigningKey key, AttemptKeyStore store) {
        return new AttemptKeyring(key, store);
    }

    @Bean
    AttemptTokenVerifier attemptTokenVerifier(AttemptKeyring keyring, MeterRegistry meterRegistry) {
        return new AttemptTokenVerifier(keyring, meterRegistry);
    }

    @Bean
    AttemptTokenIssuer attemptTokenIssuer(AttemptKeyring keyring) {
        return new AttemptTokenIssuer(keyring);
    }

    /** Its own scheduler thread, so the key never waits behind a {@code @Scheduled} task. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnAgentRuntime
    @ConditionalOnBackgroundWork
    static class ScheduleConfiguration {

        /** Runs before the web server starts, so a gateway never opens with no keys loaded and refuses a live token. */
        @Bean
        SmartInitializingSingleton attemptKeyringStart(AttemptKeyring keyring, MeterRegistry meterRegistry) {
            return () -> {
                counted("publish", keyring::publish, meterRegistry);
                counted("refresh", keyring::refresh, meterRegistry);
            };
        }

        @Bean
        ScheduledTaskRegistrar attemptKeyringTasks(AttemptKeyring keyring, MeterRegistry meterRegistry) {
            var tasks = new ScheduledTaskRegistrar();
            tasks.addFixedDelayTask(new FixedDelayTask(
                    () -> counted("refresh", keyring::refresh, meterRegistry), REFRESH, Duration.ZERO));
            tasks.addFixedDelayTask(new FixedDelayTask(
                    () -> counted(
                            "publish",
                            () -> {
                                keyring.publish();
                                keyring.removeExpired();
                            },
                            meterRegistry),
                    PUBLISH,
                    Duration.ZERO));
            return tasks;
        }

        /** A server keeps its known keys through a store outage, so only this counter shows the outage. */
        private static void counted(String task, Runnable run, MeterRegistry meterRegistry) {
            try {
                run.run();
            } catch (RuntimeException e) {
                meterRegistry
                        .counter(AgentMetrics.ATTEMPT_KEY_SYNC_FAILURES, "task", task)
                        .increment();
                log.warn("Attempt key {} failed; the known keys stay in use", task, e);
            }
        }
    }
}
