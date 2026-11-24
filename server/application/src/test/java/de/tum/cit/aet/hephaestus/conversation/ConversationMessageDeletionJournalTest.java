package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.conversation.spi.TurnRuns;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingPrivacyJournal;
import de.tum.cit.aet.hephaestus.testconfig.RecordingPrivacyJournal.Fact;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;

/** A developer's delete of their own message is a privacy fact, recorded with the ids of the message only. */
class ConversationMessageDeletionJournalTest extends BaseUnitTest {
    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

    @Mock
    private ConversationRepository conversations;

    @Mock
    private ConversationEntryRepository entries;

    @Mock
    private ConversationTurnRepository turns;

    @Mock
    private ConversationRedaction redaction;

    @Mock
    private ConversationContextPathBuilder paths;

    @Mock
    private PersonDataWriteFence writeFence;

    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private TurnRuns runs;

    @Mock
    private ConversationSlackReplies slackReplies;

    @Test
    void shouldRecordTheDeletedMessageInThePrivacyJournal() {
        var journal = new RecordingPrivacyJournal();
        var message = new ConversationEntry(
                Conversation.main(7L, 11L, NOW),
                1,
                new ConversationEntryDraft(
                        ConversationEntry.Kind.USER,
                        ConversationEntry.Surface.APP,
                        "Delete me",
                        null,
                        null,
                        null,
                        null),
                NOW);
        when(entries.findByIdAndWorkspaceId(message.getId(), 7L)).thenReturn(Optional.of(message));

        service(journal).deleteMessage(7L, message.getId());

        assertThat(journal.facts())
                .containsExactly(new Fact(
                        PrivacyJournalKind.ENTRY_DELETED, Map.of("workspaceId", 7L, "entryId", message.getId())));
    }

    private ConversationEntryService service(PrivacyJournal journal) {
        return new ConversationEntryService(
                conversations,
                entries,
                turns,
                redaction,
                paths,
                writeFence,
                events,
                runs,
                Clock.fixed(NOW, ZoneOffset.UTC),
                slackReplies,
                journal);
    }
}
