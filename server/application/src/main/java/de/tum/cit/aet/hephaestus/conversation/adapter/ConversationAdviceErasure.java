package de.tum.cit.aet.hephaestus.conversation.adapter;

import de.tum.cit.aet.hephaestus.conversation.ConversationBasis;
import de.tum.cit.aet.hephaestus.conversation.ConversationRedaction;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceErasureListener;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceStaleCause;
import java.util.Collection;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Advice that loses its text to an erasure or a withdrawal takes back the answers that read it: every entry whose
 * attempt's basis read one of the revisions, and every entry derived from those ({@link ConversationRedaction}). It
 * runs in the deleting transaction, so a failure rolls the erasure back. A retention keeps the answers, which have their
 * own retention.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class ConversationAdviceErasure implements AdviceErasureListener {
    private final ConversationBasis basis;
    private final ConversationRedaction redaction;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void staleRevisions(long workspaceId, Collection<UUID> revisionIds, AdviceStaleCause cause) {
        switch (cause) {
            case ERASURE, WITHDRAWAL ->
                redaction.redactWithReaders(basis.entriesThatReadAdvice(workspaceId, revisionIds));
            case RETENTION -> {}
        }
    }
}
