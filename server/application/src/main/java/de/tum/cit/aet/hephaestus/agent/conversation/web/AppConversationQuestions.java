package de.tum.cit.aet.hephaestus.agent.conversation.web;

import de.tum.cit.aet.hephaestus.agent.conversation.ConversationAgentProperties;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationAdmission;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.exception.ConversationRefusedException;
import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTimeline;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * A question that the developer asks in the app, as {@code SlackConversationService} takes one asked in Slack: it
 * refuses a message that is too long or a developer whom Hephaestus declines now, places the question in the main
 * conversation or in the thread of one of the developer's messages, and queues its turn.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class AppConversationQuestions {

    private final ConversationAgentProperties properties;
    private final ConversationAdmission admission;
    private final ConversationService conversations;
    private final ConversationTimeline timeline;
    private final ConversationTurnService turns;

    ConversationTurnService.Submission ask(long workspaceId, long account, ConversationTurnRequestDTO request) {
        if (request.text().length() > properties.maxPromptChars()) {
            throw new InvalidInputException(
                    "Your message is too long. The maximum is " + properties.maxPromptChars() + " characters.");
        }
        admission.refusal(workspaceId, account).ifPresent(refusal -> {
            throw new ConversationRefusedException(refusal);
        });
        Conversation main = conversations.findOrCreateMain(workspaceId, account);
        UUID conversationId = main.getId();
        UUID parentEntryId = request.parentEntryId();
        if (parentEntryId != null) {
            timeline.requireOwnedEntry(workspaceId, account, parentEntryId);
            conversationId =
                    conversations.openThread(workspaceId, parentEntryId).getId();
        }
        return turns.submit(
                workspaceId,
                conversationId,
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP,
                        request.text(),
                        request.requestId().toString(),
                        null,
                        request.pageContext()));
    }
}
