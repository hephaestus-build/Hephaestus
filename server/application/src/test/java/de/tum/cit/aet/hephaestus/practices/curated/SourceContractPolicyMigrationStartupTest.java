package de.tum.cit.aet.hephaestus.practices.curated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;

@Tag("unit")
class SourceContractPolicyMigrationStartupTest {
    @Test
    void shouldPreventContextRefreshWhenAnInstalledPolicyCannotBeUpgraded() {
        var migration = mock(SourceContractPolicyMigration.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(migration)
                .run();
        var refreshed = new AtomicBoolean();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("production");
            context.registerBean(
                    SourceContractPolicyMigrationStartup.class,
                    () -> new SourceContractPolicyMigrationStartup(migration));
            context.addApplicationListener(event -> {
                if (event instanceof ContextRefreshedEvent) {
                    refreshed.set(true);
                }
            });

            assertThatThrownBy(context::refresh).isInstanceOf(IllegalStateException.class);
            assertThat(refreshed).isFalse();
        }
    }
}
