package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.TestAsyncConfiguration;
import de.tum.cit.aet.hephaestus.testconfig.TestSecurityConfig;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.boot.context.metrics.buffering.StartupTimeline;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.UseMainMethod;
import org.springframework.context.annotation.Import;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(useMainMethod = UseMainMethod.ALWAYS)
@ActiveProfiles("test")
@Import({TestSecurityConfig.class, TestAsyncConfiguration.class})
@Tag("integration")
class StartupBudgetIntegrationTest {

    // Isolate schema creation/drop from other contexts sharing the PostgreSQL container.
    private static final PostgreSQLTestContainer.TestDatabase DATABASE =
            PostgreSQLTestContainer.createDatabase("startup_budget");

    private static final String BEAN_INSTANTIATE = "spring.beans.instantiate";

    // Database connection and schema export time are external to application bean initialization.
    private static final String JPA_WARM_UP_BEAN = "&entityManagerFactory";

    private static final Duration PER_BEAN_CEILING = Duration.ofSeconds(6);

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::jdbcUrl);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
    }

    @Autowired
    private ApplicationStartup applicationStartup;

    @Test
    void shouldKeepBeanInstantiationWithinBudgetWhenApplicationStarts() {
        var events = ((BufferingApplicationStartup) applicationStartup)
                .getBufferedTimeline()
                .getEvents();

        var beans = events.stream()
                .filter(e -> BEAN_INSTANTIATE.equals(e.getStartupStep().getName()))
                .filter(e -> e.getEndTime() != null)
                .toList();

        assertThat(beans.stream().map(StartupBudgetIntegrationTest::beanNameOf))
                .as("startup events must contain the exempt bean %s", JPA_WARM_UP_BEAN)
                .contains(JPA_WARM_UP_BEAN);

        var slowest = beans.stream()
                .filter(e -> !JPA_WARM_UP_BEAN.equals(beanNameOf(e)))
                .max((a, b) -> a.getDuration().compareTo(b.getDuration()))
                .orElseThrow(() -> new AssertionError("no " + BEAN_INSTANTIATE + " events captured"));

        assertThat(slowest.getDuration())
                .as(
                        "slowest bean instantiation %s (%s); budget %s",
                        slowest.getDuration(), beanNameOf(slowest), PER_BEAN_CEILING)
                .isLessThan(PER_BEAN_CEILING);
    }

    private static String beanNameOf(StartupTimeline.TimelineEvent event) {
        for (StartupStep.Tag tag : event.getStartupStep().getTags()) {
            if ("beanName".equals(tag.getKey())) {
                return tag.getValue();
            }
        }
        return "bean name not tagged";
    }
}
