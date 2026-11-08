package de.tum.cit.aet.hephaestus.practices.adapter;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.WorkFeedbackThreadChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** A resolution or reopening permanently hands that owned thread to its people. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class FeedbackThreadStateListener {
    private final FeedbackThreadStateRepository rows;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(WorkFeedbackThreadChangedEvent event) {
        rows.protect(event.workspaceId(), event.itemId(), event.threadRef());
    }
}
