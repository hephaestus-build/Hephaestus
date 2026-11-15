package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.response.FeedbackResponse;
import de.tum.cit.aet.hephaestus.practices.feedback.response.FeedbackResponseRepository;
import de.tum.cit.aet.hephaestus.practices.metrics.PracticeMetrics;
import de.tum.cit.aet.hephaestus.practices.metrics.PracticeTrustMeters;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackUsefulness;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class FeedbackResponseServiceTest {

    final FeedbackRepository feedback = mock(FeedbackRepository.class);
    final FeedbackResponseRepository responses = mock(FeedbackResponseRepository.class);
    final FeedbackVisibilityService visibility = mock(FeedbackVisibilityService.class);
    final CurrentDeveloperLookup current = mock(CurrentDeveloperLookup.class);
    final PersonDataWriteFence privacy = mock(PersonDataWriteFence.class);
    final Instant now = Instant.parse("2026-10-14T00:00:00Z");
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final FeedbackResponseService service = new FeedbackResponseService(
            responses,
            feedback,
            visibility,
            current,
            Clock.fixed(now, ZoneOffset.UTC),
            privacy,
            new PracticeTrustMeters(meters));
    final UUID id = UUID.randomUUID();

    @BeforeEach
    void setup() {
        when(privacy.holdForAccountWrite(anyLong())).thenReturn(true);
        when(responses.save(any())).thenAnswer(a -> a.getArgument(0));
        when(current.currentDeveloperIdElseThrow()).thenReturn(2L);
    }

    void shown(@Nullable Instant at) {
        when(feedback.lockByIdAndWorkspaceId(id, 1))
                .thenReturn(Optional.of(Feedback.builder()
                        .id(id)
                        .developerId(2)
                        .state(FeedbackState.UNCONFIRMED)
                        .deliveredAt(at)
                        .build()));
    }

    @Test
    void shouldAllowAResponseToAConfirmedSiblingDespiteOtherUnknownVisibility() {
        shown(now);
        var response = service.respond(
                1,
                id,
                2,
                FeedbackResolution.DISAGREE,
                FeedbackUsefulness.UNHELPFUL,
                "The test is in the companion change.");
        assertThat(response.resolution()).isEqualTo(FeedbackResolution.DISAGREE);
        assertThat(response.createdAt()).isEqualTo(now);
        assertThat(meters.get(PracticeMetrics.FEEDBACK_RESPONSE)
                        .tag("resolution", "disagree")
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void shouldCountAResolutionOnceAndNotItsLaterComment() {
        shown(now);
        when(responses.findCurrent(1, id))
                .thenReturn(Optional.of(FeedbackResponse.builder()
                        .resolution(FeedbackResolution.ADDRESSED)
                        .comment("first")
                        .build()));

        service.respond(1, id, 2, FeedbackResolution.ADDRESSED, null, "second");
        service.respond(1, id, 2, null, FeedbackUsefulness.HELPFUL, null);

        assertThat(meters.get(PracticeMetrics.FEEDBACK_RESPONSE).counters().stream()
                        .mapToDouble(Counter::count)
                        .sum())
                .isZero();
    }

    @Test
    void shouldHideUnknownOrOtherDevelopersFeedback() {
        shown(null);
        assertThatThrownBy(() -> service.respond(1, id, 2, null, FeedbackUsefulness.HELPFUL, null))
                .isInstanceOf(EntityNotFoundException.class);
        shown(now);
        assertThatThrownBy(() -> service.respond(1, id, 3, null, FeedbackUsefulness.HELPFUL, null))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void shouldRefuseAResponseDuringAccountErasure() {
        when(privacy.holdForAccountWrite(2)).thenReturn(false);
        assertThatThrownBy(() -> service.respond(1, id, 2, null, FeedbackUsefulness.HELPFUL, null))
                .isInstanceOf(ResponseStatusException.class);
        verify(responses, never()).save(any());
    }

    @Test
    void shouldRequireAnExplanationForDisagreement() {
        assertThatThrownBy(() -> service.respond(1, id, 2, FeedbackResolution.DISAGREE, null, " "))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void shouldAppendACompleteClearWithoutDeletingHistory() {
        shown(now);
        var workspace = new WorkspaceContext(1L, "w", "Workspace", null, null, Set.of());
        var cleared = service.deleteResponse(workspace, id);
        assertThat(cleared.resolution()).isNull();
        assertThat(cleared.usefulness()).isNull();
        assertThat(cleared.createdAt()).isEqualTo(now);
        var capture = ArgumentCaptor.forClass(FeedbackResponse.class);
        verify(responses).save(capture.capture());
        assertThat(capture.getValue().getFeedbackId()).isEqualTo(id);
    }

    @Test
    void shouldRejectResponsesAfterWithdrawal() {
        shown(now);
        when(visibility.withdrawn(1, id)).thenReturn(true);
        assertThatThrownBy(() -> service.respond(1, id, 2, FeedbackResolution.ADDRESSED, null, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void shouldLeaveEveryMissingFieldOffTheWire() {
        // As the server's own mapper: a null property is left out.
        var mapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
        var json = mapper.valueToTree(FeedbackResponseDTO.empty());
        assertThat(json.propertyNames()).isEmpty();
    }
}
