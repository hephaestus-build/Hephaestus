package de.tum.cit.aet.hephaestus.practices.adapter;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceStaleCause;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ConversationFeedbackErasureAdapter implements ConversationFeedbackErasure {
    private final PracticesPrivacyRepository rows;
    private final DerivedResultEraser eraser;

    @Override
    @Transactional
    public int eraseForThreads(long ws, Collection<Long> threads) {
        if (threads.isEmpty()) return 0;
        return eraser.erase(ws, rows.conversationResultIdsForThreads(ws, threads), AdviceStaleCause.WITHDRAWAL);
    }

    @Override
    @Transactional
    public int expireForThreads(long ws, Collection<Long> threads) {
        if (threads.isEmpty()) return 0;
        return eraser.erase(ws, rows.conversationResultIdsForThreads(ws, threads), AdviceStaleCause.RETENTION);
    }

    @Override
    @Transactional
    public int eraseAllConversationForWorkspace(long ws) {
        return eraser.erase(ws, rows.conversationResultIds(ws), AdviceStaleCause.WITHDRAWAL);
    }

    @Override
    @Transactional
    public int eraseConversationFeedbackAboutUser(long ws, long account) {
        return eraser.erase(ws, rows.conversationResultIdsForDeveloper(ws, account), AdviceStaleCause.WITHDRAWAL);
    }
}
