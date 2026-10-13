package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionHealth;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;

/** Starts Heph turns in a test as a worker does, with an admission that admits every turn on the test model. */
public final class MentorTurnStarts {

    private MentorTurnStarts() {}

    public static MentorTurnStarter admitting(MentorTurnService turns, MeterRegistry metrics) {
        MentorAdmission admission = mock(MentorAdmission.class);
        when(admission.admit(anyLong(), anyLong())).thenReturn(LlmCatalogTestFixtures.admittedMentorConfig());
        return new MentorTurnStarter(
                turns, admission, metrics, new LlmConnectionHealth(CircuitBreakerRegistry.ofDefaults()));
    }

    /** Asks {@code question} in the app and starts its turn as {@code worker}, with a five-minute lease. */
    public static MentorTurn askAndStart(
            MentorTurnService turns,
            MeterRegistry metrics,
            long workspaceId,
            UUID conversationId,
            String question,
            String worker) {
        String requestId = UUID.randomUUID().toString();
        turns.submit(
                workspaceId,
                conversationId,
                new MentorTurnService.Question(MentorTurn.Surface.APP, question, requestId, null, null));
        MentorTurn started = admitting(turns, metrics)
                .startNext(worker, Duration.ofMinutes(5))
                .orElseThrow()
                .turn();
        if (!started.getRequestId().equals(requestId)) {
            throw new IllegalStateException("Another queued turn started first");
        }
        return started;
    }
}
