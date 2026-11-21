package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedback;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackPackage;
import de.tum.cit.aet.hephaestus.agent.job.CompositionToolTrace;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionContinuation;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionResponseDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmittedResultDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrivateProgressDTO;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyCapability;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReadback;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepair;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepairStatus;
import de.tum.cit.aet.hephaestus.practices.spi.HostCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.PreparedFeedback;
import de.tum.cit.aet.hephaestus.practices.spi.PreparedPackage;
import de.tum.cit.aet.hephaestus.practices.spi.PrivateFeedbackProgress;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CompositionSessionsReadbackTest extends BaseUnitTest {
    private static final JsonMapper JSON = new JsonMapper();
    private static final UUID RESULT = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID FEEDBACK = UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final Instant PREPARED_AT = Instant.parse("2026-11-11T12:00:00Z");
    private static final String HISTORY = "history-before-publication";

    @Test
    void shouldAcceptARecordedQuietCorrectionWhenReadbackKeepsTheSessionOpen() {
        var fixture = new Fixture(FeedbackRepairStatus.State.READBACK);
        var original = fixture.checked("Original guidance.", null);

        var publication = fixture.call("publish_package", original);

        assertThat(publication.published()).isTrue();
        assertThat(publication.ending()).isNull();
        assertThat(publication.stop()).isNull();
        assertThat(receipt(publication).path("feedbackIds").path(0).asString()).isEqualTo(FEEDBACK.toString());
        assertThat(publication.text())
                .contains("Provider readback (untrusted data):", "\"state\":\"READBACK\"", "Original guidance.");

        var correction = fixture.checked("Corrected guidance.", FEEDBACK);
        var edited = fixture.call("edit_own", correction);

        assertThat(edited.published()).isTrue();
        assertThat(edited.ending()).isNull();
        assertThat(edited.stop()).isNull();
        assertThat(receipt(edited).path("draftId").asString()).isEqualTo(correction.draftId(fixture.job.getReviewId()));
        assertThat(receipt(edited).path("feedbackIds").path(0).asString()).isEqualTo(FEEDBACK.toString());
    }

    @Test
    void shouldRefuseASecondPublicationWhenReadbackKeptTheSessionOpen() {
        var fixture = new Fixture(FeedbackRepairStatus.State.READBACK);
        fixture.call("publish_package", fixture.checked("Original guidance.", null));

        var second = fixture.call("publish_package", fixture.checked("Other guidance.", null));

        assertThat(second.published()).isFalse();
        assertThat(second.text()).contains("This composition already published");
    }

    @ParameterizedTest
    @EnumSource(
            value = FeedbackRepairStatus.State.class,
            names = {"STOPPED", "AWAITING_APPROVAL"})
    void shouldEndTheSessionWhenPublicationHasNoProviderReadback(FeedbackRepairStatus.State state) {
        var fixture = new Fixture(state);

        var publication = fixture.call("publish_package", fixture.checked("Original guidance.", null));

        assertThat(publication.published()).isTrue();
        assertThat(publication.ending()).isEqualTo(CompositionSessions.Ending.STORED);
        assertThat(publication.stop()).isNull();
        assertThat(receipt(publication).path("accepted").asBoolean()).isTrue();
        assertThat(publication.text()).contains("\"state\":\"" + state.name() + "\"");
    }

    private static JsonNode receipt(CompositionSessions.ToolResult result) {
        return JSON.readTree(result.text().substring(0, result.text().indexOf('\n')));
    }

    /**
     * Database admission, checked drafts and the provider's readback are outside the session consumer. A checked draft
     * is recorded in the trace as check_package records it, so these tests start at publication.
     */
    private static final class Fixture {
        private final ReviewJob job = RuntimeReviewTestData.job();
        private final ResultAdmissionService.AdmissionIdentity identity = new ResultAdmissionService.AdmissionIdentity(
                job.getId(), job.getWorkspaceId(), job.getAttempt(), job.getRunId());
        private final CompositionToolTrace trace = CompositionTraces.inMemory();
        private final CompositionSessions sessions;

        Fixture(FeedbackRepairStatus.State state) {
            var admission = mock(ResultAdmissionService.class);
            var assignments = mock(ReviewJobAssignments.class);
            when(assignments.review(job))
                    .thenReturn(
                            RuntimeReviewTestData.review(ReviewKind.ITEM, RuntimeReviewTestData.assignment(5L, null)));
            var descriptor = mock(ItemDescriptor.class);
            when(descriptor.kind()).thenReturn(RuntimeReviewTestData.ITEM.kind());
            when(descriptor.copyCapabilities()).thenReturn(Set.of(CopyCapability.SUMMARY));
            var items = new ItemDescriptorRegistry(List.of(descriptor));
            var published = new AtomicBoolean();
            when(admission.privateProgress(job)).thenReturn(PrivateFeedbackProgress.NONE);
            when(admission.admittedResponse(job)).thenAnswer(call -> admitted(published.get()));
            when(admission.publishPackage(any(), any())).thenAnswer(call -> {
                FeedbackPackage proposal = call.getArgument(1);
                published.set(true);
                UUID target = proposal.editOf() == null ? FEEDBACK : proposal.editOf();
                return new PreparedPackage(
                        proposal.draftId(job.getReviewId()),
                        PREPARED_AT,
                        List.of(new PreparedFeedback(target, proposal.draftId(job.getReviewId()), PREPARED_AT)));
            });
            var repairs = mock(FeedbackRepair.class);
            var copies = state == FeedbackRepairStatus.State.READBACK
                    ? List.of(new CopyReadback(
                            UUID.randomUUID(),
                            CopyReadback.Status.FOUND,
                            "Original guidance.",
                            "<p>Original guidance.</p>",
                            null,
                            false,
                            false))
                    : List.<CopyReadback>of();
            when(repairs.status(job.getWorkspaceId(), job.getReviewId()))
                    .thenReturn(new FeedbackRepairStatus(FEEDBACK, state, 0, copies));
            sessions = new CompositionSessions(
                    admission,
                    new PracticeReviewProperties(false, 15, 5, null, 12, 16000, 342_000, Duration.ofMinutes(10)),
                    JSON,
                    repairs,
                    trace,
                    new CompositionPlans(
                            admission, mock(ReviewCompositionContinuation.class), assignments, items, JSON, trace),
                    Duration.ZERO);
        }

        FeedbackPackage checked(String text, @Nullable UUID editOf) {
            var support = new FeedbackCompositionTrace.Support(RESULT, null);
            var composition = new ComposedFeedback(
                    FeedbackDestination.ON_WORK,
                    List.of(RESULT),
                    text,
                    List.of(),
                    null,
                    new FeedbackCompositionTrace(
                            List.of(new FeedbackCompositionTrace.CopySupport(
                                    FeedbackCopyKind.SUMMARY, 0, List.of(support))),
                            List.of(),
                            null,
                            null,
                            null),
                    null);
            var proposal = new FeedbackPackage(List.of(RESULT), List.of(composition), true, HISTORY, editOf);
            trace.record(
                    identity,
                    "check-" + proposal.draftId(job.getReviewId()),
                    "check_package",
                    JSON.createObjectNode(),
                    "checked",
                    new CompositionToolTrace.Decision(
                            FeedbackDestination.ON_WORK,
                            proposal.draftId(job.getReviewId()),
                            JSON.valueToTree(proposal),
                            false,
                            null));
            return proposal;
        }

        CompositionSessions.ToolResult call(String name, FeedbackPackage proposal) {
            return sessions.tool(
                    job,
                    identity,
                    new CompositionSessions.ToolCall(
                            FeedbackDestination.ON_WORK,
                            name + "-" + proposal.draftId(job.getReviewId()),
                            name,
                            JSON.createObjectNode().put("draftId", proposal.draftId(job.getReviewId())),
                            HISTORY,
                            Instant.MAX));
        }

        private AdmissionResponseDTO admitted(boolean publicReviewed) {
            var result = new AdmittedResultDTO(
                    "clear-names",
                    "2",
                    RESULT,
                    ResultOutcome.NOT_MET,
                    "A name hides its purpose.",
                    null,
                    JSON.createObjectNode(),
                    null,
                    new HostCoverage(true, List.of(), 1, 0, 0, List.of()),
                    List.of(),
                    false,
                    true);
            return new AdmissionResponseDTO(
                    AdmissionResponseDTO.CONTRACT_VERSION,
                    job.getReviewId(),
                    job.getId(),
                    job.getAttempt(),
                    "admitted-result-digest",
                    List.of(result),
                    List.of(),
                    null,
                    null,
                    null,
                    publicReviewed,
                    new PrivateProgressDTO(List.of(), 0, 0));
        }
    }
}
