package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.event.AgentContextChangedEvent;
import de.tum.cit.aet.hephaestus.core.event.AiChoiceChangedEvent;
import de.tum.cit.aet.hephaestus.core.event.RepositoryMonitoringChangedEvent;
import de.tum.cit.aet.hephaestus.core.event.ScmMirrorErasedEvent;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationContextChangesTest extends BaseUnitTest {

    @Test
    void shouldTellTheWorkersOfTheWorkspaceWhenWhatItsTurnsMayReadChanges() {
        List<Object> published = new ArrayList<>();
        var changes = new ConversationContextChanges(published::add, mock(ConversationRepository.class));

        changes.visibilityChanged(new AgentContextChangedEvent(1));
        changes.monitoringChanged(new RepositoryMonitoringChangedEvent(2, List.of(5L)));
        changes.mirrorErased(new ScmMirrorErasedEvent(3));

        assertThat(published)
                .containsExactly(
                        new ConversationLedgerEvents.ContextChanged(1),
                        new ConversationLedgerEvents.ContextChanged(2),
                        new ConversationLedgerEvents.ContextChanged(3));
    }

    @Test
    void shouldTellEveryWorkspaceOfTheAccountWhenItsAiChoiceChanges() {
        List<Object> published = new ArrayList<>();
        ConversationRepository conversations = mock(ConversationRepository.class);
        when(conversations.findMemberWorkspaceIds(9L)).thenReturn(List.of(4L, 5L));

        new ConversationContextChanges(published::add, conversations).aiChoiceChanged(new AiChoiceChangedEvent(9));

        assertThat(published)
                .containsExactly(
                        new ConversationLedgerEvents.ContextChanged(4), new ConversationLedgerEvents.ContextChanged(5));
    }
}
