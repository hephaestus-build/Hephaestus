package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.WorkFeedbackThreadChangedEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScmFeedbackThreadStateListener {
    private final ScmFeedbackThreadStateRepository rows;
    private final ApplicationEventPublisher events;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(ScmDomainEvent.ReviewThreadEvent event) {
        for (var thread : rows.find(event.thread().id())) {
            events.publishEvent(new WorkFeedbackThreadChangedEvent(
                    thread.getWorkspaceId(), thread.getItemId(), thread.getThreadRef()));
        }
    }
}
