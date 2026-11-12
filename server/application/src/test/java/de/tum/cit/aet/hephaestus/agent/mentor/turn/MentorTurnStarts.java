package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionHealth;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

/** Starts Heph turns in a test as a worker does, with an admission that admits every turn on the test model. */
public final class MentorTurnStarts {

    private MentorTurnStarts() {}

    /** A starter that admits every turn on the test model. */
    public static MentorTurnStarter admitting(ApplicationContext beans, MeterRegistry metrics) {
        return admitting(beans, metrics, null);
    }

    /** As {@link #admitting(ApplicationContext, MeterRegistry)}, with at most {@code cap} turns at once. */
    public static MentorTurnStarter admitting(ApplicationContext beans, MeterRegistry metrics, @Nullable Integer cap) {
        MentorAdmission admission = mock(MentorAdmission.class);
        when(admission.admit(anyLong(), anyLong())).thenReturn(LlmCatalogTestFixtures.admittedMentorConfig());
        return new MentorTurnStarter(
                beans.getBean(MentorTurnService.class),
                admission,
                beans.getBean(MentorTurnRepository.class),
                beans.getBean(PersonDataWriteFence.class),
                metrics,
                new LlmConnectionHealth(CircuitBreakerRegistry.ofDefaults()),
                beans.getBean(AgentRuns.class),
                capacity(cap),
                beans.getBean(TransactionTemplate.class));
    }

    /** The default capacity numbers, with at most {@code turns} turns at once. */
    public static AgentCapacityProperties capacity(@Nullable Integer turns) {
        return new AgentCapacityProperties(
                Duration.ofMinutes(5),
                Duration.ofSeconds(60),
                turns,
                Duration.ofSeconds(25),
                5,
                Duration.ofMinutes(10),
                Duration.ofSeconds(30),
                Duration.ofMinutes(2),
                Duration.ofMinutes(1),
                Duration.ofMinutes(15));
    }

    /** Asks a question in the app and starts its turn. */
    public static MentorTurn askAndStart(
            ApplicationContext beans, MeterRegistry metrics, long workspaceId, UUID conversationId, String question) {
        String requestId = UUID.randomUUID().toString();
        beans.getBean(MentorTurnService.class)
                .submit(
                        workspaceId,
                        conversationId,
                        new MentorTurnService.Question(MentorTurn.Surface.APP, question, requestId, null, null));
        MentorTurn started = admitting(beans, metrics).startNext().orElseThrow().turn();
        if (!started.getRequestId().equals(requestId)) {
            throw new IllegalStateException("Another queued turn started first");
        }
        return started;
    }
}
