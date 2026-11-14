package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A private destination that its session opened can close before the session's next tool call, because each call reads
 * the admission again. The call then answers the model and records the honest reason; it never fails the request.
 */
@Tag("unit")
class CompositionClosedDestinationTest {

    @Test
    void shouldStopTheSessionAndRecordRoutingUnavailableWhenTheRoutingReadFailsAfterTheSessionOpened()
            throws Exception {
        var basis = ComposerReplay.basis(ComposerReplay.load("resumed-private"));
        var server = new ComposerReplay.Server(basis);
        var sessions = server.sessions();
        var identity = new ResultAdmissionService.AdmissionIdentity(
                server.job.getId(), server.job.getWorkspaceId(), server.job.getAttempt(), ComposerReplay.RUN);
        var session =
                (CompositionSessions.Session) sessions.next(server.job, identity, Set.of(FeedbackDestination.ON_WORK))
                        .orElseThrow();
        var destination = session.destination();
        assertThat(destination).isNotEqualTo(FeedbackDestination.ON_WORK);

        // The destination's routing read fails between the session's start and its first check.
        boolean page = destination == FeedbackDestination.PRACTICE_PAGE;
        var read = (ObjectNode) basis.admission().path(page ? "practicePageSupport" : "conversationEligibility");
        read.put("state", "UNAVAILABLE");
        read.putArray(page ? "practices" : "results");
        var answer = sessions.tool(
                server.job,
                identity,
                new CompositionSessions.ToolCall(
                        destination,
                        "call-1",
                        "check_package",
                        JsonNodeFactory.instance.objectNode(),
                        session.publicationContext(),
                        Instant.now().plusSeconds(60)));

        assertThat(answer.published()).isFalse();
        assertThat(answer.text()).contains("cannot be read now");
        assertThat(sessions.close(server.job, identity, Map.of())).isEqualTo(CompositionStop.ROUTING_UNAVAILABLE);
    }

    @Test
    void shouldStopTheSessionWithUnexpectedWhenTheServerFailsDuringATool() throws Exception {
        var basis = ComposerReplay.basis(ComposerReplay.load("resumed-private"));
        var server = new ComposerReplay.Server(basis);
        var sessions = server.sessions();
        var identity = new ResultAdmissionService.AdmissionIdentity(
                server.job.getId(), server.job.getWorkspaceId(), server.job.getAttempt(), ComposerReplay.RUN);
        var session =
                (CompositionSessions.Session) sessions.next(server.job, identity, Set.of(FeedbackDestination.ON_WORK))
                        .orElseThrow();

        // A read the server cannot interpret stands in for any defect of the server during the call.
        ((ObjectNode) basis.admission().path("practicePageSupport")).put("state", "NOT_A_STATE");
        var answer = sessions.tool(
                server.job,
                identity,
                new CompositionSessions.ToolCall(
                        session.destination(),
                        "call-1",
                        "check_package",
                        JsonNodeFactory.instance.objectNode(),
                        session.publicationContext(),
                        Instant.now().plusSeconds(60)));

        assertThat(answer.published()).isFalse();
        assertThat(answer.ending()).isEqualTo(CompositionSessions.Ending.STOPPED);
        assertThat(answer.stop()).isEqualTo(CompositionStop.UNEXPECTED);
    }
}
