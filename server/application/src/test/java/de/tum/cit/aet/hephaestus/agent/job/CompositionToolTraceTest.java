package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** A composition reads back only what its own attempt's tool calls decided. */
@Tag("unit")
class CompositionToolTraceTest {
    private final JsonMapper mapper = new JsonMapper();
    private final ResultAdmissionService admission = mock(ResultAdmissionService.class);
    private final ReviewCompositionBasisRepository bases = mock(ReviewCompositionBasisRepository.class);
    private final CompositionToolTrace trace =
            new CompositionToolTrace(admission, bases, mock(ReviewCompositionContinuation.class));

    @Test
    void shouldKeepOnlyTheDecisionAndNeverFailWhenTheTraceIsFull() {
        var job = RuntimeReviewTestData.job();
        var basis = new ReviewCompositionBasis();
        var identity = new ResultAdmissionService.AdmissionIdentity(job.getId(), 1L, 1, UUID.randomUUID());
        when(admission.ownedJob(identity)).thenReturn(job);
        when(bases.findByWorkspaceIdAndJobId(any(Long.class), any())).thenReturn(Optional.of(basis));
        var words = mapper.createObjectNode().put("summary", "w".repeat(8 * 1024));

        // Room for a decision, not for the check's words and package.
        basis.setPayload(filled(1024));
        trace.record(
                identity,
                "check",
                "check_package",
                words,
                "checked",
                new CompositionToolTrace.Decision(FeedbackDestination.PRACTICE_PAGE, "draft", words, false, null));
        assertThat(trace.decisions(identity))
                .containsExactly(new CompositionToolTrace.Decision(
                        FeedbackDestination.PRACTICE_PAGE, "draft", null, false, null));

        // No room at all: the call is not traced, and the composition goes on.
        basis.setPayload(filled(16));
        trace.record(
                identity,
                "publish",
                "publish_package",
                mapper.createObjectNode().put("draftId", "draft"),
                "accepted",
                new CompositionToolTrace.Decision(FeedbackDestination.PRACTICE_PAGE, "draft", null, true, null));
        assertThat(trace.decisions(identity)).isEmpty();
        assertThat(basis.getPayload().path("tools").toString().length())
                .isLessThanOrEqualTo((int) JobEvidenceFiles.TRACE_BYTE_BUDGET);
    }

    /** A trace whose earlier calls left this many bytes of its budget. */
    private ObjectNode filled(int left) {
        var payload = mapper.createObjectNode();
        var tools = payload.putArray("tools");
        var earlier =
                tools.addObject().put("attempt", 0).put("callId", "earlier").put("tool", "check_package");
        int used = tools.toString().length() + "\"outcome\":\"\",".length();
        earlier.put("outcome", "x".repeat((int) JobEvidenceFiles.TRACE_BYTE_BUDGET - used - left));
        return payload;
    }

    @Test
    void shouldReadOnlyTheDecisionsOfItsOwnAttemptWhenAnEarlierAttemptPublished() {
        var job = RuntimeReviewTestData.job();
        var basis = new ReviewCompositionBasis();
        basis.setPayload(mapper.readTree("""
                {"tools":[
                  {"attempt":0,"callId":"a","tool":"publish_package","arguments":{},"outcome":"accepted",
                   "destination":"PRACTICE_PAGE","draftId":"earlier","published":true},
                  {"attempt":1,"callId":"b","tool":"check_package","arguments":{},"outcome":"checked",
                   "destination":"PRACTICE_PAGE","draftId":"mine","proposal":{"basisResultIds":[]}},
                  {"attempt":1,"callId":"c","tool":"publish_package","arguments":{},"outcome":"refused",
                   "destination":"ON_WORK","refusal":"PUBLIC_HISTORY_CHANGED"},
                  {"attempt":1,"callId":"d","tool":"check_package","arguments":{},"outcome":"before decisions"}
                ]}
                """));
        var identity = new ResultAdmissionService.AdmissionIdentity(job.getId(), 1L, 1, UUID.randomUUID());
        when(admission.ownedJob(identity)).thenReturn(job);
        when(bases.findByWorkspaceIdAndJobId(any(Long.class), any())).thenReturn(Optional.of(basis));

        assertThat(trace.decisions(identity))
                .containsExactly(
                        new CompositionToolTrace.Decision(
                                FeedbackDestination.PRACTICE_PAGE,
                                "mine",
                                mapper.readTree("{\"basisResultIds\":[]}"),
                                false,
                                null),
                        new CompositionToolTrace.Decision(
                                FeedbackDestination.ON_WORK,
                                null,
                                null,
                                false,
                                FeedbackStopReason.PUBLIC_HISTORY_CHANGED));
    }
}
