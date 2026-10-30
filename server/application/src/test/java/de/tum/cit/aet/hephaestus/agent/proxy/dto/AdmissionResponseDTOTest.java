package de.tum.cit.aet.hephaestus.agent.proxy.dto;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.handler.FeedbackCompositionService;
import de.tum.cit.aet.hephaestus.practices.spi.ClaimedCoverage;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationDecision;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationEligibility;
import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionContext;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.PracticePageDecision;
import de.tum.cit.aet.hephaestus.practices.spi.PracticePageSupport;
import de.tum.cit.aet.hephaestus.practices.spi.PrivateFeedbackHistory;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class AdmissionResponseDTOTest {
    @Test
    void shouldSerializeTheAdmissionAndItsRoutingForTheSharedRunnerFixture() throws Exception {
        var mapper = JsonMapper.builder().build();
        var resultId = UUID.fromString("30000000-0000-4000-8000-000000000001");
        var readAt = Instant.parse("2026-10-14T00:00:00Z");
        var read = new FeedbackCompositionService.Read(
                new FeedbackCompositionContext(
                        new PracticePageSupport(
                                PracticePageSupport.State.COMPLETE,
                                readAt,
                                List.of(new PracticePageSupport.PracticeSupport(
                                        "handles-errors",
                                        PracticePageDecision.GATE_REFUSED,
                                        FeedbackStopReason.NO_AI,
                                        List.of())),
                                null),
                        new ConversationEligibility(
                                ConversationEligibility.State.COMPLETE,
                                readAt,
                                List.of(new ConversationEligibility.ResultDecision(
                                        resultId, ConversationDecision.GATE_REFUSED, FeedbackStopReason.NO_AI)))),
                List.of(),
                List.of(),
                new PrivateFeedbackHistory(
                        readAt,
                        30,
                        false,
                        1,
                        List.of(
                                said(
                                        "50000000-0000-4000-8000-000000000001",
                                        FeedbackDestination.CONVERSATION,
                                        FeedbackState.DELIVERED,
                                        "Hephaestus said the catch drops the error.",
                                        Instant.parse("2026-10-02T00:00:00Z"),
                                        Currentness.CURRENT,
                                        null),
                                said(
                                        "50000000-0000-4000-8000-000000000002",
                                        FeedbackDestination.PRACTICE_PAGE,
                                        FeedbackState.DELIVERED,
                                        "A card whose claim no longer holds.",
                                        Instant.parse("2026-09-02T00:00:00Z"),
                                        Currentness.STALE,
                                        null),
                                said(
                                        "50000000-0000-4000-8000-000000000003",
                                        FeedbackDestination.CONVERSATION,
                                        FeedbackState.READY,
                                        "Notes queued for Hephaestus about dropped errors.",
                                        null,
                                        Currentness.CURRENT,
                                        "handles-errors"))));
        var problem = new AdmittedProblemDTO(
                UUID.fromString("40000000-0000-4000-8000-000000000001"),
                Severity.MAJOR,
                "The failure is dropped",
                resultId,
                0,
                null,
                "The catch returns without recording the error.",
                List.of(),
                null,
                "Record the failure.",
                true);
        var result = new AdmittedResultDTO(
                "handles-errors",
                "42",
                resultId,
                ResultOutcome.NOT_MET,
                "The failure is dropped",
                "The catch returns without recording the error.",
                mapper.readTree("{\"citations\":[]}"),
                null,
                new ClaimedCoverage(true, null, 1, 1, 1),
                List.of(problem),
                true,
                true);
        var response = new AdmissionResponseDTO(
                AdmissionResponseDTO.CONTRACT_VERSION,
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                UUID.fromString("20000000-0000-4000-8000-000000000001"),
                0,
                "a".repeat(64),
                List.of(result),
                List.of(new AdmissionResponseDTO.Withheld("names-things", "42", "NO_EVIDENCE")),
                FeedbackCompositionService.page(read),
                FeedbackCompositionService.conversation(read),
                FeedbackCompositionService.privateHistory(read),
                false,
                new PrivateProgressDTO(List.of(), 0, 0));
        try (var fixture = Objects.requireNonNull(getClass().getResourceAsStream("/agent/admission-response.json"))) {
            assertThat(mapper.readTree(mapper.writeValueAsString(response))).isEqualTo(mapper.readTree(fixture));
        }
    }

    private static PrivateFeedbackHistory.Entry said(
            String id,
            FeedbackDestination destination,
            FeedbackState state,
            String body,
            @Nullable Instant deliveredAt,
            Currentness currentness,
            @Nullable String replaceablePracticeSlug) {
        var earlier = UUID.fromString("60000000-0000-4000-8000-000000000001");
        return new PrivateFeedbackHistory.Entry(
                new FeedbackHistoryEntry(
                        UUID.fromString(id),
                        UUID.fromString("70000000-0000-4000-8000-000000000001"),
                        42L,
                        9L,
                        destination,
                        state,
                        body,
                        Instant.parse("2026-09-01T00:00:00Z"),
                        deliveredAt,
                        List.of(earlier),
                        List.of(),
                        List.of(),
                        null,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        true,
                        null),
                List.of(new PrivateFeedbackHistory.Basis(earlier, "handles-errors", 7L, ResultOutcome.NOT_MET)),
                currentness,
                false,
                replaceablePracticeSlug);
    }
}
