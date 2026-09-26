package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.conversation.ConversationalDeliveryReconciler;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.TranslatorState;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.ChatMessage;
import de.tum.cit.aet.hephaestus.mentor.ChatMessageRepository;
import de.tum.cit.aet.hephaestus.mentor.ChatThread;
import de.tum.cit.aet.hephaestus.mentor.ChatThreadRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class MentorTurnPersistenceDeliveryOutcomeTest extends BaseUnitTest {

    private final ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
    private final ConversationalDeliveryReconciler reconciler = mock(ConversationalDeliveryReconciler.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private MentorTurnPersistence persistence;

    @BeforeEach
    void setUp() {
        persistence = new MentorTurnPersistence(
                mock(ChatThreadRepository.class),
                chatMessageRepository,
                mock(WorkspaceRepository.class),
                reconciler,
                mock(LlmUsageRecorder.class),
                transactionManager);
    }

    @Test
    void silentOutcomeSuppressesPreparedFeedback() {
        enableTransaction();
        Fixture fixture = fixture();

        persistence.recordDelivery(fixture.cookie(), fixture.state(), MentorChannel.DeliveryOutcome.INSTANCE_SILENCED);

        verify(reconciler).suppressForSilentMode(1L, 2L, List.of(fixture.observationId()));
        verify(reconciler, never()).reconcile(anyLong(), anyLong(), any(), any());
    }

    @Test
    void deliveredOutcomeReconcilesPreparedFeedback() {
        enableTransaction();
        Fixture fixture = fixture();

        persistence.recordDelivery(fixture.cookie(), fixture.state(), MentorChannel.DeliveryOutcome.DELIVERED);

        verify(reconciler, never()).suppressForSilentMode(anyLong(), anyLong(), any());
        verify(reconciler).reconcile(1L, 2L, fixture.cookie().assistantMessageId(), List.of(fixture.observationId()));
    }

    @Test
    void notDeliveredOutcomeDoesNotConsumePreparedFeedback() {
        enableTransaction();
        Fixture fixture = fixture();

        persistence.recordDelivery(fixture.cookie(), fixture.state(), MentorChannel.DeliveryOutcome.NOT_DELIVERED);

        verify(reconciler, never()).suppressForSilentMode(anyLong(), anyLong(), any());
        verify(reconciler, never()).reconcile(anyLong(), anyLong(), any(), any());
    }

    private Fixture fixture() {
        UUID assistantId = UUID.randomUUID();
        UUID observationId = UUID.randomUUID();
        Workspace workspace = new Workspace();
        workspace.setId(1L);
        User user = new User();
        user.setId(2L);
        ChatThread thread = new ChatThread();
        thread.setWorkspace(workspace);
        thread.setUser(user);
        ChatMessage assistant = new ChatMessage();
        assistant.setId(assistantId);
        assistant.setThread(thread);
        when(chatMessageRepository.findById(assistantId)).thenReturn(java.util.Optional.of(assistant));
        TranslatorState state = new TranslatorState(assistantId);
        state.recordDataObservation(observationId);
        return new Fixture(cookie(assistantId), state, observationId);
    }

    private void enableTransaction() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    private static MentorTurnPersistence.TurnPersistenceCookie cookie(UUID assistantId) {
        return new MentorTurnPersistence.TurnPersistenceCookie(
                UUID.randomUUID(),
                UUID.randomUUID(),
                assistantId,
                Instant.now(),
                "model",
                mock(LlmPriceSnapshot.class));
    }

    private record Fixture(
            MentorTurnPersistence.TurnPersistenceCookie cookie, TranslatorState state, UUID observationId) {}
}
