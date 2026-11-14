package de.tum.cit.aet.hephaestus.conversation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stops the start while the conversation retention setting that {@code hephaestus.feedback-retention} replaced is still
 * set. Ignoring it would erase conversations at the default instead of the window the operator chose, irreversibly.
 * It keeps the name the setting had when it was retired, as the operator set it.
 */
@Component
@ConditionalOnProperty("hephaestus.mentor.conversation-retention")
class RetiredConversationRetention {
    RetiredConversationRetention() {
        throw new IllegalStateException("HEPHAESTUS_MENTOR_CONVERSATION_RETENTION is replaced by"
                + " HEPHAESTUS_FEEDBACK_RETENTION, which also sets how long feedback and practice results are kept."
                + " Set the new one and remove the old one.");
    }
}
