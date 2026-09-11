package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResolutionCountsDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseRequestDTO;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.Reaction;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.ReactionRepository;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

class FeedbackResponseServiceTest extends BaseUnitTest {

    private static final Long WORKSPACE_ID = 1L;
    private static final Long CONTRIBUTOR_ID = 10L;
    private static final Long OTHER_USER_ID = 20L;
    private static final UUID FEEDBACK_ID = UUID.randomUUID();

    @Mock
    private ReactionRepository reactionRepository;

    @Mock
    private FeedbackRepository feedbackRepository;

    @Mock
    private CurrentDeveloperLookup currentDeveloperLookup;

    @Captor
    private ArgumentCaptor<Reaction> reactionCaptor;

    private FeedbackResponseService service;
    private WorkspaceContext workspaceContext;

    @BeforeEach
    void setUp() {
        service = new FeedbackResponseService(reactionRepository, feedbackRepository, currentDeveloperLookup);
        workspaceContext = new WorkspaceContext(WORKSPACE_ID, "test-ws", "Test WS", null, null, false, false, Set.of());
        org.mockito.Mockito.lenient()
                .when(currentDeveloperLookup.currentDeveloperIdElseThrow())
                .thenReturn(CONTRIBUTOR_ID);
        org.mockito.Mockito.lenient()
                .when(currentDeveloperLookup.currentDeveloperId())
                .thenReturn(Optional.of(CONTRIBUTOR_ID));
    }

    private void stubCurrentResponse(
            @Nullable FeedbackUsefulness usefulness,
            @Nullable FeedbackResolution resolution,
            @Nullable String comment) {
        stubCurrentResponse(usefulness, resolution, comment, false);
    }

    private void stubCurrentResponse(
            @Nullable FeedbackUsefulness usefulness,
            @Nullable FeedbackResolution resolution,
            @Nullable String comment,
            boolean absentBeforeReplacement) {
        var projection = org.mockito.Mockito.mock(ReactionRepository.CurrentResponseProjection.class);
        when(projection.getUsefulness()).thenReturn(usefulness == null ? null : usefulness.name());
        when(projection.getResolution()).thenReturn(resolution == null ? null : resolution.name());
        when(projection.getComment()).thenReturn(comment);
        when(projection.getRespondedAt()).thenReturn(Instant.now());
        var response = when(reactionRepository.findCurrentResponse(FEEDBACK_ID, CONTRIBUTOR_ID));
        if (absentBeforeReplacement) {
            response.thenReturn(Optional.empty()).thenReturn(Optional.of(projection));
        } else {
            response.thenReturn(Optional.of(projection));
        }
    }

    private Feedback createFeedback(Long recipientUserId) {
        return Feedback.builder()
                .id(FEEDBACK_ID)
                .recipientUserId(recipientUserId)
                .workspaceId(WORKSPACE_ID)
                .deliveryState(FeedbackDeliveryState.DELIVERED)
                .build();
    }

    @Nested
    class SubmitResponse {

        @Test
        void shouldRecordUsefulnessAndResolutionTogether() {
            Feedback feedback = createFeedback(CONTRIBUTOR_ID);
            when(feedbackRepository.findByIdAndWorkspaceIdAndRecipientUserIdAndDeliveryState(
                            FEEDBACK_ID, WORKSPACE_ID, CONTRIBUTOR_ID, FeedbackDeliveryState.DELIVERED))
                    .thenReturn(Optional.of(feedback));
            var request = new FeedbackResponseRequestDTO(
                    FeedbackUsefulness.HELPFUL, FeedbackResolution.ADDRESSED, "Clear and actionable.");
            stubCurrentResponse(
                    FeedbackUsefulness.HELPFUL, FeedbackResolution.ADDRESSED, "Clear and actionable.", true);

            FeedbackResponseDTO result = service.replaceResponse(workspaceContext, FEEDBACK_ID, request);

            verify(reactionRepository).save(reactionCaptor.capture());
            Reaction saved = reactionCaptor.getValue();
            assertThat(saved.getUsefulness()).isEqualTo(FeedbackUsefulness.HELPFUL);
            assertThat(saved.getResolution()).isEqualTo(FeedbackResolution.ADDRESSED);
            assertThat(result.usefulness()).isEqualTo(FeedbackUsefulness.HELPFUL);
            assertThat(result.resolution()).isEqualTo(FeedbackResolution.ADDRESSED);
        }

        @Test
        void shouldRejectEmptyResponse() {
            Feedback feedback = createFeedback(CONTRIBUTOR_ID);
            when(feedbackRepository.findByIdAndWorkspaceIdAndRecipientUserIdAndDeliveryState(
                            FEEDBACK_ID, WORKSPACE_ID, CONTRIBUTOR_ID, FeedbackDeliveryState.DELIVERED))
                    .thenReturn(Optional.of(feedback));

            assertThatThrownBy(() -> service.replaceResponse(
                            workspaceContext, FEEDBACK_ID, new FeedbackResponseRequestDTO(null, null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("usefulness or resolution");
        }

        @Test
        void addressedFeedbackSaves() {
            Feedback feedback = createFeedback(CONTRIBUTOR_ID);
            when(feedbackRepository.findByIdAndWorkspaceIdAndRecipientUserIdAndDeliveryState(
                            FEEDBACK_ID, WORKSPACE_ID, CONTRIBUTOR_ID, FeedbackDeliveryState.DELIVERED))
                    .thenReturn(Optional.of(feedback));

            var request = new FeedbackResponseRequestDTO(null, FeedbackResolution.ADDRESSED, null);
            service.replaceResponse(workspaceContext, FEEDBACK_ID, request);

            verify(reactionRepository).save(reactionCaptor.capture());
            Reaction saved = reactionCaptor.getValue();
            assertThat(saved.getReactorUserId()).isEqualTo(CONTRIBUTOR_ID);
            assertThat(saved.getResolution()).isEqualTo(FeedbackResolution.ADDRESSED);
        }

        @Test
        void nonDeliveredFeedbackThrows() {
            when(feedbackRepository.findByIdAndWorkspaceIdAndRecipientUserIdAndDeliveryState(
                            FEEDBACK_ID, WORKSPACE_ID, CONTRIBUTOR_ID, FeedbackDeliveryState.DELIVERED))
                    .thenReturn(Optional.empty());

            var request = new FeedbackResponseRequestDTO(null, FeedbackResolution.ADDRESSED, null);
            assertThatThrownBy(() -> service.replaceResponse(workspaceContext, FEEDBACK_ID, request))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        void disputedWithExplanationSaves() {
            Feedback feedback = createFeedback(CONTRIBUTOR_ID);
            when(feedbackRepository.findByIdAndWorkspaceIdAndRecipientUserIdAndDeliveryState(
                            FEEDBACK_ID, WORKSPACE_ID, CONTRIBUTOR_ID, FeedbackDeliveryState.DELIVERED))
                    .thenReturn(Optional.of(feedback));

            var request =
                    new FeedbackResponseRequestDTO(null, FeedbackResolution.DISPUTED, "The AI is wrong about this");
            service.replaceResponse(workspaceContext, FEEDBACK_ID, request);

            verify(reactionRepository).save(reactionCaptor.capture());
            assertThat(reactionCaptor.getValue().getResolution()).isEqualTo(FeedbackResolution.DISPUTED);
            assertThat(reactionCaptor.getValue().getExplanation()).isEqualTo("The AI is wrong about this");
        }

        @Test
        void notApplicableSaves() {
            Feedback feedback = createFeedback(CONTRIBUTOR_ID);
            when(feedbackRepository.findByIdAndWorkspaceIdAndRecipientUserIdAndDeliveryState(
                            FEEDBACK_ID, WORKSPACE_ID, CONTRIBUTOR_ID, FeedbackDeliveryState.DELIVERED))
                    .thenReturn(Optional.of(feedback));

            var request = new FeedbackResponseRequestDTO(
                    null, FeedbackResolution.NOT_APPLICABLE, "Not relevant to my use case");
            service.replaceResponse(workspaceContext, FEEDBACK_ID, request);

            verify(reactionRepository).save(reactionCaptor.capture());
            assertThat(reactionCaptor.getValue().getResolution()).isEqualTo(FeedbackResolution.NOT_APPLICABLE);
        }

        @Test
        void disputedWithoutExplanationThrows() {
            Feedback feedback = createFeedback(CONTRIBUTOR_ID);
            when(feedbackRepository.findByIdAndWorkspaceIdAndRecipientUserIdAndDeliveryState(
                            FEEDBACK_ID, WORKSPACE_ID, CONTRIBUTOR_ID, FeedbackDeliveryState.DELIVERED))
                    .thenReturn(Optional.of(feedback));

            var request = new FeedbackResponseRequestDTO(null, FeedbackResolution.DISPUTED, null);
            assertThatThrownBy(() -> service.replaceResponse(workspaceContext, FEEDBACK_ID, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("comment is required");
        }

        @Test
        void disputedWithBlankExplanationThrows() {
            Feedback feedback = createFeedback(CONTRIBUTOR_ID);
            when(feedbackRepository.findByIdAndWorkspaceIdAndRecipientUserIdAndDeliveryState(
                            FEEDBACK_ID, WORKSPACE_ID, CONTRIBUTOR_ID, FeedbackDeliveryState.DELIVERED))
                    .thenReturn(Optional.of(feedback));

            var request = new FeedbackResponseRequestDTO(null, FeedbackResolution.DISPUTED, "   ");
            assertThatThrownBy(() -> service.replaceResponse(workspaceContext, FEEDBACK_ID, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("comment is required");
        }

        @Test
        void nonRecipientThrows() {
            when(currentDeveloperLookup.currentDeveloperIdElseThrow()).thenReturn(OTHER_USER_ID);
            when(feedbackRepository.findByIdAndWorkspaceIdAndRecipientUserIdAndDeliveryState(
                            FEEDBACK_ID, WORKSPACE_ID, OTHER_USER_ID, FeedbackDeliveryState.DELIVERED))
                    .thenReturn(Optional.empty());

            var request = new FeedbackResponseRequestDTO(null, FeedbackResolution.ADDRESSED, null);
            assertThatThrownBy(() -> service.replaceResponse(workspaceContext, FEEDBACK_ID, request))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        void feedbackNotFoundThrows() {
            when(feedbackRepository.findByIdAndWorkspaceIdAndRecipientUserIdAndDeliveryState(
                            FEEDBACK_ID, WORKSPACE_ID, CONTRIBUTOR_ID, FeedbackDeliveryState.DELIVERED))
                    .thenReturn(Optional.empty());

            var request = new FeedbackResponseRequestDTO(null, FeedbackResolution.ADDRESSED, null);
            assertThatThrownBy(() -> service.replaceResponse(workspaceContext, FEEDBACK_ID, request))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    @Nested
    class GetLatestResponse {

        @Test
        void returnsLatestWhenPresent() {
            Feedback feedback = createFeedback(CONTRIBUTOR_ID);
            when(feedbackRepository.findByIdAndWorkspaceIdAndRecipientUserIdAndDeliveryState(
                            FEEDBACK_ID, WORKSPACE_ID, CONTRIBUTOR_ID, FeedbackDeliveryState.DELIVERED))
                    .thenReturn(Optional.of(feedback));

            stubCurrentResponse(null, FeedbackResolution.ADDRESSED, null);

            Optional<FeedbackResponseDTO> result = service.getResponse(workspaceContext, FEEDBACK_ID);

            assertThat(result).isPresent();
            assertThat(result.get().resolution()).isEqualTo(FeedbackResolution.ADDRESSED);
            assertThat(result.get().feedbackId()).isEqualTo(FEEDBACK_ID);
        }

        @Test
        void returnsEmptyWhenNone() {
            Feedback feedback = createFeedback(CONTRIBUTOR_ID);
            when(feedbackRepository.findByIdAndWorkspaceIdAndRecipientUserIdAndDeliveryState(
                            FEEDBACK_ID, WORKSPACE_ID, CONTRIBUTOR_ID, FeedbackDeliveryState.DELIVERED))
                    .thenReturn(Optional.of(feedback));

            Optional<FeedbackResponseDTO> result = service.getResponse(workspaceContext, FEEDBACK_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("answers a signed-in non-developer with nothing rather than an error")
        void returnsEmptyForACallerWhoIsNotASyncedDeveloper() {
            // A read answers absence with absence — the contract CurrentDeveloperLookup states, and the one
            // the practice standing and review-runs surfaces already keep.
            when(currentDeveloperLookup.currentDeveloperId()).thenReturn(Optional.empty());

            assertThat(service.getResponse(workspaceContext, FEEDBACK_ID)).isEmpty();
        }

        @Test
        void throwsWhenFeedbackNotInWorkspace() {
            when(feedbackRepository.findByIdAndWorkspaceIdAndRecipientUserIdAndDeliveryState(
                            FEEDBACK_ID, WORKSPACE_ID, CONTRIBUTOR_ID, FeedbackDeliveryState.DELIVERED))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getResponse(workspaceContext, FEEDBACK_ID))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    @Nested
    class GetResolutionCounts {

        @Test
        @DisplayName("counts zero for a signed-in non-developer instead of failing")
        void returnsZeroesForACallerWhoIsNotASyncedDeveloper() {
            when(currentDeveloperLookup.currentDeveloperId()).thenReturn(Optional.empty());

            FeedbackResolutionCountsDTO result = service.getResolutionCounts(workspaceContext);

            assertThat(result.addressed()).isZero();
            assertThat(result.disputed()).isZero();
            assertThat(result.notApplicable()).isZero();
        }

        @Test
        void returnsCorrectCounts() {
            var addressedProjection = new ReactionRepository.ActionCountProjection() {
                @Override
                public String getAction() {
                    return FeedbackResolution.ADDRESSED.name();
                }

                @Override
                public Long getCount() {
                    return 3L;
                }
            };
            var disputedProjection = new ReactionRepository.ActionCountProjection() {
                @Override
                public String getAction() {
                    return FeedbackResolution.DISPUTED.name();
                }

                @Override
                public Long getCount() {
                    return 1L;
                }
            };

            when(reactionRepository.countByReactorAndWorkspaceGroupByAction(CONTRIBUTOR_ID, WORKSPACE_ID))
                    .thenReturn(List.of(addressedProjection, disputedProjection));

            FeedbackResolutionCountsDTO result = service.getResolutionCounts(workspaceContext);

            assertThat(result.addressed()).isEqualTo(3L);
            assertThat(result.disputed()).isEqualTo(1L);
            assertThat(result.notApplicable()).isEqualTo(0L);
        }

        @Test
        @DisplayName("returns all zeros when no response exists")
        void returnsZerosWhenEmpty() {
            when(reactionRepository.countByReactorAndWorkspaceGroupByAction(CONTRIBUTOR_ID, WORKSPACE_ID))
                    .thenReturn(List.of());

            FeedbackResolutionCountsDTO result = service.getResolutionCounts(workspaceContext);

            assertThat(result.addressed()).isZero();
            assertThat(result.disputed()).isZero();
            assertThat(result.notApplicable()).isZero();
        }
    }
}
