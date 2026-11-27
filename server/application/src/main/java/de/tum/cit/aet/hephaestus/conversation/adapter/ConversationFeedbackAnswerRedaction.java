package de.tum.cit.aet.hephaestus.conversation.adapter;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationRedaction;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonFeedbackAnswerRedaction;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The answers of a turn that claimed feedback about an erased person go, with what derives from them (#5150). */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class ConversationFeedbackAnswerRedaction implements PersonFeedbackAnswerRedaction {
    private final ConversationEntryRepository entries;
    private final ConversationRedaction redaction;

    @Override
    public void redactClaimedAnswers(
            Map<Long, ? extends Collection<UUID>> claimedTurns, Collection<UUID> boundAnswers) {
        var answers = new LinkedHashSet<UUID>(boundAnswers);
        claimedTurns.forEach((workspace, turns) -> {
            if (!turns.isEmpty()) answers.addAll(entries.findUnredactedAnswerIds(workspace, turns));
        });
        // Locks the conversation trees before the caller deletes the copies, as a receipt takes them.
        redaction.redactPersonCopies(answers);
    }
}
