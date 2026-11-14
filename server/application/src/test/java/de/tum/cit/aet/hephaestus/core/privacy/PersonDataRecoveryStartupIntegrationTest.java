package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("erasure-recovery")
@Import(PersonDataRecoveryStartupIntegrationTest.Ordering.class)
class PersonDataRecoveryStartupIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private PersonDataRecovery recovery;

    @Autowired
    private Observed observed;

    @DynamicPropertySource
    static void recoveryManifest(DynamicPropertyRegistry registry) throws IOException {
        var directory = Files.createTempDirectory("erasure-recovery-startup-");
        directory.toFile().deleteOnExit();
        var manifest = Files.writeString(
                directory.resolve("manifest.json"),
                "{\"version\":2,\"workspacePurges\":[],\"disconnects\":[],\"restorePoint\":\"2026-01-01T00:00:00Z\",\"noLaterErasures\":true,\"people\":[]}");
        manifest.toFile().deleteOnExit();
        registry.add("hephaestus.privacy.recovery-manifest", manifest::toString);
        registry.add(RuntimeRole.SERVER_PROPERTY, () -> "true");
    }

    @Test
    void shouldBindTheRecoveryProfileAndCompleteBeforeSchedulersAndConsumersStart() {
        assertThat(recovery.isRunning()).isTrue();
        assertThat(observed.beforeSchedulers()).isTrue();
        assertThat(observed.beforeConsumers()).isTrue();
    }

    private record Observed(AtomicBoolean beforeSchedulers, AtomicBoolean beforeConsumers) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class Ordering {
        @Bean
        Observed observed() {
            return new Observed(new AtomicBoolean(), new AtomicBoolean());
        }

        @Bean
        ApplicationListener<ContextRefreshedEvent> beforeSchedulers(PersonDataRecovery recovery, Observed observed) {
            return event -> observed.beforeSchedulers().set(recovery.isRunning());
        }

        @Bean
        ApplicationListener<ApplicationReadyEvent> beforeConsumers(PersonDataRecovery recovery, Observed observed) {
            return event -> observed.beforeConsumers().set(recovery.isRunning());
        }
    }
}
