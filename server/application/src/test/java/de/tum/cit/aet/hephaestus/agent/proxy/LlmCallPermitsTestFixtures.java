package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public final class LlmCallPermitsTestFixtures {
    static final LlmCallPoolProperties PROPERTIES =
            new LlmCallPoolProperties(Duration.ofSeconds(20), Duration.ofSeconds(30));
    /** The default of {@code hephaestus.agent.capacity.permit-aging}. */
    public static final Duration PERMIT_AGING = Duration.ofSeconds(30);

    private LlmCallPermitsTestFixtures() {}

    /** A deadline no test reaches, for a call whose test is not about its deadline. */
    static Instant laterThanAnyTest() {
        return Instant.now().plus(Duration.ofHours(1));
    }

    /** The database's time. A pool's pause is stored and read in it, by every worker. */
    static Instant databaseNow(JdbcTemplate jdbc) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class))
                .toInstant();
    }

    public static LlmCallPermits worker(ApplicationContext context, String workerId) {
        return new LlmCallPermits(
                context.getBean(LlmCallPoolRepository.class),
                context.getBean(LlmCallPermitRepository.class),
                context.getBean(TransactionTemplate.class),
                PROPERTIES,
                PERMIT_AGING,
                workerId,
                new SimpleMeterRegistry(),
                context.getBean(LlmModelResolver.class));
    }
}
