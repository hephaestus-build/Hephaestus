package de.tum.cit.aet.hephaestus.integration.slack.conversation;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerEvents;
import de.tum.cit.aet.hephaestus.conversation.ConversationSlackMessages;
import de.tum.cit.aet.hephaestus.conversation.ConversationSlackReplies;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class SlackConversationProjectorTest extends BaseUnitTest {

    /** A payload without an account reads as account 0: the projector binds no owner and reads nothing. */
    @Test
    void shouldBindNoOwnerAndReadNothingWhenTheChangeNamesNoAccount() {
        var messages = mock(ConversationSlackMessages.class);
        var owners = mock(OwnerScope.class);
        var transactions = mock(PlatformTransactionManager.class);
        var executor = mock(ActionExecutor.class);

        new SlackConversationProjector(messages, mock(ConversationSlackReplies.class), executor, owners, transactions)
                .turnChanged(new ConversationLedgerEvents.TurnChanged(
                        1L,
                        UUID.randomUUID(),
                        0L,
                        UUID.randomUUID(),
                        ConversationTurn.Status.DONE,
                        ConversationTurn.Surface.SLACK,
                        1));

        verifyNoInteractions(owners, transactions, messages, executor);
    }
}
