package de.tum.cit.aet.hephaestus.practices.adapter;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.WorkFeedbackThreadChangedEvent;
import de.tum.cit.aet.hephaestus.practices.feedback.thread.FeedbackThreadOwnership;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** A person's resolution or reopening permanently hands that owned thread to its people. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class FeedbackThreadStateListener {
    private final FeedbackThreadStateRepository rows;
    private final FeedbackThreadOwnership ownership;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(WorkFeedbackThreadChangedEvent event) {
        if (ownership.own(event.workspaceId(), event.itemId(), event.threadRef(), event.resolved(), event.actor()))
            return;
        rows.protect(event.workspaceId(), event.itemId(), event.threadRef());
    }
}
