package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * A write that commits between the DM's lookup and the load of the main it found makes the DM resolve again: an account
 * absorption that deletes the found main and moves its DM to the main it keeps, or a binding of the found main to
 * another DM.
 */
@Tag("unit")
class ConversationSlackMainTest {
    private static final long WORKSPACE = 1;
    private static final UUID ABSORBED = UUID.randomUUID();
    private static final UUID KEPT = UUID.randomUUID();

    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final ConversationService service = new ConversationService(
            conversations,
            mock(ConversationEntryRepository.class),
            mock(PersonDataWriteFence.class),
            mock(ApplicationEventPublisher.class),
            Clock.systemUTC());

    @Test
    void shouldFindTheKeptMainWhenAnAbsorptionDeletedTheFoundMain() {
        Conversation kept = Conversation.main(WORKSPACE, 2, Instant.EPOCH);
        kept.bindSlack("T", "U", "D");
        when(conversations.findSlackMainId(WORKSPACE, "T", "U", "D"))
                .thenReturn(Optional.of(ABSORBED))
                .thenReturn(Optional.of(KEPT));
        when(conversations.findByIdAndWorkspaceId(ABSORBED, WORKSPACE)).thenReturn(Optional.empty());
        when(conversations.findByIdAndWorkspaceId(KEPT, WORKSPACE)).thenReturn(Optional.of(kept));

        assertThat(service.findMainBySlack(WORKSPACE, "T", "U", "D")).containsSame(kept);
    }

    @Test
    void shouldNotFindAMainThatABindingMovedToAnotherDm() {
        Conversation moved = Conversation.main(WORKSPACE, 2, Instant.EPOCH);
        moved.bindSlack("T", "U", "D2");
        Conversation kept = Conversation.main(WORKSPACE, 3, Instant.EPOCH);
        kept.bindSlack("T", "U", "D");
        when(conversations.findSlackMainId(WORKSPACE, "T", "U", "D"))
                .thenReturn(Optional.of(ABSORBED))
                .thenReturn(Optional.of(KEPT));
        when(conversations.findByIdAndWorkspaceId(ABSORBED, WORKSPACE)).thenReturn(Optional.of(moved));
        when(conversations.findByIdAndWorkspaceId(KEPT, WORKSPACE)).thenReturn(Optional.of(kept));

        assertThat(service.findMainBySlack(WORKSPACE, "T", "U", "D")).containsSame(kept);
    }

    @Test
    void shouldFindNoMainWhenTheDmNamesTheSameMissingMainAgain() {
        when(conversations.findSlackMainId(WORKSPACE, "T", "U", "D")).thenReturn(Optional.of(ABSORBED));
        when(conversations.findByIdAndWorkspaceId(ABSORBED, WORKSPACE)).thenReturn(Optional.empty());

        assertThat(service.findMainBySlack(WORKSPACE, "T", "U", "D")).isEmpty();
        verify(conversations, times(2)).findSlackMainId(WORKSPACE, "T", "U", "D");
    }
}
