package de.tum.cit.aet.hephaestus.testconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import liquibase.Scope;
import liquibase.changelog.ChangeLogHistoryServiceFactory;
import liquibase.database.core.PostgresDatabase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementContextAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.tomcat.autoconfigure.actuate.web.server.TomcatServletManagementContextAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextRefreshedEvent;

/** A real parent and management child, closed in the order that a context cache eviction closes them. */
@Tag("integration")
class ClosedTestContextReleaserIntegrationTest {

    @Test
    void shouldLetGarbageCollectorReclaimContextWhenPausedContextCloses() {
        WeakReference<ConfigurableApplicationContext> closed = pauseAndClose();
        // Logback keeps the environment of the last application that started, as it does between test contexts.
        new SpringApplication(LaterApplication.class)
                .run("--spring.main.web-application-type=none", "--spring.main.banner-mode=off")
                .close();

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    System.gc();
                    return closed.get() == null;
                });
    }

    /** In a method of its own, so that no local variable of the test keeps the context reachable. */
    private static WeakReference<ConfigurableApplicationContext> pauseAndClose() {
        var child = new AtomicReference<ConfigurableApplicationContext>();
        var application = new SpringApplication(HeldContextApplication.class);
        application.addListeners((ApplicationListener<ApplicationEvent>) event -> {
            if (event instanceof ContextRefreshedEvent refreshed
                    && refreshed.getApplicationContext().getParent() != null
                    && refreshed.getApplicationContext() instanceof ConfigurableApplicationContext context) {
                child.set(context);
            }
        });

        var parent = application.run(
                "--server.port=0",
                "--server.address=127.0.0.1",
                "--management.server.port=0",
                "--management.server.address=127.0.0.1",
                "--spring.main.banner-mode=off");
        try {
            assertThat(child.get()).isNotNull();
            parent.pause();
            assertThat(child.get().isRunning()).isFalse();
        } finally {
            parent.close();
        }

        assertThat(child.get().isClosed()).isTrue();
        return new WeakReference<>(parent);
    }

    /** What a spy's real instance holds in a test context: a way back to the context. */
    static class HoldsContext {

        final ApplicationContext context;

        HoldsContext(ApplicationContext context) {
            this.context = context;
        }
    }

    /** What Liquibase's database holds in a test context: a way back to the context, there through a pooled connection. */
    static class DatabaseHoldingContext extends PostgresDatabase {

        final ApplicationContext context;

        DatabaseHoldingContext(ApplicationContext context) {
            this.context = context;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LaterApplication {}

    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
        TomcatServletWebServerAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class,
        EndpointAutoConfiguration.class,
        WebEndpointAutoConfiguration.class,
        ManagementContextAutoConfiguration.class,
        TomcatServletManagementContextAutoConfiguration.class,
    })
    static class HeldContextApplication {

        @Bean
        HoldsContext spyHoldingContext(ApplicationContext context) {
            return Mockito.spy(new HoldsContext(context));
        }

        @Bean
        Object liquibaseHistoryService(ApplicationContext context) {
            return Scope.getCurrentScope()
                    .getSingleton(ChangeLogHistoryServiceFactory.class)
                    .getChangeLogService(new DatabaseHoldingContext(context));
        }
    }
}
