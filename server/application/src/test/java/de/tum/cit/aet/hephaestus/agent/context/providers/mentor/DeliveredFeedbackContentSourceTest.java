package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository.FeedbackObservationVisibility;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository.RecipientFeedbackRow;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class DeliveredFeedbackContentSourceTest extends BaseUnitTest {

    private static final Instant AT = Instant.parse("2026-06-17T08:30:00Z");

    @Mock
    UserRepository userRepository;

    @Mock
    FeedbackRepository feedbackRepository;

    @Mock
    FeedbackObservationRepository feedbackObservationRepository;

    @Mock
    ConversationConsentGate conversationConsentGate;

    @Mock
    ObservationVisibilityPolicy visibilityPolicy;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    DeliveredFeedbackContentSource provider;

    @Test
    @DisplayName("no feedback → empty arrays")
    void emptyDefaults() throws Exception {
        givenUser();
        givenRows();

        JsonNode root = contribute();

        assertThat(root.get("user").get("login").asString()).isEqualTo("octo");
        assertThat(root.get("deliveredFeedback")).isEmpty();
        assertThat(root.get("feedbackStates")).isEmpty();
    }

    @Test
    @DisplayName("ships the delivered body; a blank one keeps its status but no text, and null artifacts are omitted")
    void shipsRenderedBodyAndSkipsBlank() throws Exception {
        givenUser();
        Row withBody = delivered(ArtifactKinds.PULL_REQUEST, 575L, "Nice work scoping this PR.");
        Row blank = delivered(null, null, "   ");
        givenRows(withBody, blank);
        authorize(withBody, blank);

        JsonNode root = contribute();

        assertThat(root.get("deliveredFeedback")).hasSize(1);
        JsonNode only = root.get("deliveredFeedback").get(0);
        assertThat(only.get("feedbackId").asString()).isEqualTo(withBody.getId().toString());
        assertThat(only.get("surface").asString()).isEqualTo("IN_CONTEXT");
        assertThat(only.get("artifactKind").asString()).isEqualTo("scm.pull_request");
        assertThat(only.get("artifactId").asLong()).isEqualTo(575L);
        assertThat(only.get("body").asString()).isEqualTo("Nice work scoping this PR.");
        JsonNode blankState = root.get("feedbackStates").get(1);
        assertThat(blankState.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(blankState.has("artifactKind")).isFalse();
        assertThat(blankState.has("artifactId")).isFalse();
    }

    @Test
    @DisplayName("source authorization is re-evaluated for every turn")
    void authorizationIsReevaluatedForEveryTurn() throws Exception {
        givenUser();
        givenRows();

        contribute();
        contribute();

        verify(feedbackRepository, times(2))
                .findRecentReceivableForRecipient(
                        eq(1L), eq(2L), any(Instant.class), any(), any(), any(Pageable.class));
    }

    @Test
    void keepsAConversationStatusButNeverItsBody() throws Exception {
        givenUser();
        Row chat = new Row(
                UUID.randomUUID(),
                FeedbackChannel.IN_CHAT,
                FeedbackDeliveryState.DELIVERED,
                null,
                null,
                AT,
                AT,
                "composer notes for the mentor");
        givenRows(chat);
        authorize(chat);

        JsonNode root = contribute();

        assertThat(root.get("deliveredFeedback")).isEmpty();
        assertThat(root.get("feedbackStates").get(0).get("status").asString()).isEqualTo("DELIVERED");
        assertThat(root.toString()).doesNotContain("composer notes for the mentor");
    }

    @Test
    void withholdsFeedbackWhenItsObservationIsNoLongerVisible() {
        givenUser();
        Row row = delivered(ArtifactKinds.PULL_REQUEST, 575L, "Previously delivered");
        givenRows(row);
        FeedbackObservationVisibility binding = binding(row);
        when(feedbackObservationRepository.findForVisibility(eq(1L), any())).thenReturn(List.of(binding));
        when(visibilityPolicy.permitsAll(eq(1L), any(), eq(SourceUsePurpose.CONVERSATIONAL_MENTORING)))
                .thenReturn(Set.of());

        ObjectNode root = provider.buildPayload(1L, 2L);

        assertThat(root.path("deliveredFeedback")).isEmpty();
        assertThat(root.path("feedbackStates")).isEmpty();
    }

    @Test
    void readsAFixedNumberOfPagesWhenEveryRowIsHidden() {
        givenUser();
        Row beyondBudget = delivered(ArtifactKinds.PULL_REQUEST, 999L, "beyond-the-budget");
        AtomicInteger pages = new AtomicInteger();
        when(feedbackRepository.findRecentReceivableForRecipient(
                        eq(1L), eq(2L), any(Instant.class), any(), any(), any(Pageable.class)))
                .thenAnswer(invocation -> pages.getAndIncrement() < 5
                        ? Stream.generate(() -> delivered(ArtifactKinds.PULL_REQUEST, 1L, "hidden-body"))
                                .limit(30)
                                .toList()
                        : List.of(beyondBudget));
        authorize(beyondBudget);

        ObjectNode root = provider.buildPayload(1L, 2L);

        verify(feedbackRepository, times(5))
                .findRecentReceivableForRecipient(
                        eq(1L), eq(2L), any(Instant.class), any(), any(), any(Pageable.class));
        assertThat(root.path("feedbackStates")).isEmpty();
        assertThat(root.toString()).doesNotContain("hidden-body", "beyond-the-budget");
    }

    private JsonNode contribute() throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        provider.contribute(new ContextRequest.MentorChatRequest(1L, 2L, UUID.randomUUID()), files);
        return objectMapper.readTree(files.get("inputs/context/delivered_feedback.json"));
    }

    private void givenUser() {
        User user = new User();
        user.setLogin("octo");
        when(userRepository.findById(eq(2L))).thenReturn(Optional.of(user));
    }

    private void givenRows(RecipientFeedbackRow... rows) {
        when(feedbackRepository.findRecentReceivableForRecipient(
                        eq(1L), eq(2L), any(Instant.class), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(rows));
    }

    private void authorize(Row... rows) {
        List<FeedbackObservationVisibility> bindings = new ArrayList<>();
        Set<UUID> permitted = new HashSet<>();
        for (Row row : rows) {
            FeedbackObservationVisibility binding = binding(row);
            bindings.add(binding);
            permitted.add(binding.getObservation().getId());
        }
        when(feedbackObservationRepository.findForVisibility(eq(1L), any())).thenReturn(bindings);
        when(visibilityPolicy.permitsAll(eq(1L), any(), eq(SourceUsePurpose.CONVERSATIONAL_MENTORING)))
                .thenReturn(permitted);
    }

    private static FeedbackObservationVisibility binding(Row row) {
        Observation observation = mock(Observation.class);
        when(observation.getId()).thenReturn(UUID.randomUUID());
        FeedbackObservationVisibility binding = mock(FeedbackObservationVisibility.class);
        when(binding.getFeedbackId()).thenReturn(row.getId());
        when(binding.getObservation()).thenReturn(observation);
        return binding;
    }

    private static Row delivered(@Nullable ArtifactKind artifactKind, @Nullable Long artifactId, String body) {
        return new Row(
                UUID.randomUUID(),
                FeedbackChannel.IN_CONTEXT,
                FeedbackDeliveryState.DELIVERED,
                artifactKind,
                artifactId,
                AT,
                AT,
                body);
    }

    private record Row(
            UUID getId,
            FeedbackChannel getChannel,
            FeedbackDeliveryState getDeliveryState,
            @Nullable ArtifactKind getArtifactKind,
            @Nullable Long getArtifactId,
            Instant getCreatedAt,
            @Nullable Instant getDeliveredAt,
            @Nullable String getBody)
            implements RecipientFeedbackRow {}
}
