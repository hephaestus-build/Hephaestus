package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

public final class LlmCallPermitsTestFixtures {
    static final LlmCallPoolProperties PROPERTIES =
            new LlmCallPoolProperties(Duration.ofSeconds(20), Duration.ofSeconds(30));

    private LlmCallPermitsTestFixtures() {}

    public static LlmCallPermits worker(ApplicationContext context, String workerId) {
        return new LlmCallPermits(
                context.getBean(LlmCallPoolRepository.class),
                context.getBean(LlmCallPermitRepository.class),
                context.getBean(TransactionTemplate.class),
                PROPERTIES,
                workerId,
                new SimpleMeterRegistry(),
                context.getBean(LlmModelResolver.class));
    }
}
