package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
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

    /**
     * A starter that admits every turn and records only the admission, not a usage measurement: for tests that do not
     * bill. A test that bills uses {@link #admitting(MentorTurnService, MentorAttemptUsageService, MeterRegistry)}.
     */
    public static MentorTurnStarter admitting(MentorTurnService turns, MeterRegistry metrics) {
        MentorAttemptUsageService admissionOnly = mock(MentorAttemptUsageService.class);
        doAnswer(call -> {
                    MentorTurnAdmission admitted = call.getArgument(4);
                    turns.admit(
                            call.getArgument(0),
                            call.getArgument(1),
                            call.getArgument(2),
                            call.getArgument(3),
                            admitted.toJson());
                    return true;
                })
                .when(admissionOnly)
                .admit(anyLong(), any(), anyInt(), any(), any());
        return admitting(turns, admissionOnly, metrics);
    }

    public static MentorTurnStarter admitting(
            MentorTurnService turns, MentorAttemptUsageService attemptUsage, MeterRegistry metrics) {
        MentorAdmission admission = mock(MentorAdmission.class);
        when(admission.admit(anyLong(), anyLong())).thenReturn(LlmCatalogTestFixtures.admittedMentorConfig());
        return new MentorTurnStarter(
                turns, admission, attemptUsage, metrics, new LlmConnectionHealth(CircuitBreakerRegistry.ofDefaults()));
    }

    /** Asks a question in the app and claims its turn with a five-minute lease. */
    public static MentorTurn askAndStart(
            MentorTurnService turns, MeterRegistry metrics, long workspaceId, UUID conversationId, String question) {
        String requestId = UUID.randomUUID().toString();
        turns.submit(
                workspaceId,
                conversationId,
                new MentorTurnService.Question(MentorTurn.Surface.APP, question, requestId, null, null));
        MentorTurn started = admitting(turns, metrics)
                .startNext(Duration.ofMinutes(5))
                .orElseThrow()
                .turn();
        if (!started.getRequestId().equals(requestId)) {
            throw new IllegalStateException("Another queued turn started first");
        }
        return started;
    }
}
