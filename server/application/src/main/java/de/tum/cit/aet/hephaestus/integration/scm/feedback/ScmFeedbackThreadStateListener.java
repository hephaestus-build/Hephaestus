package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.WorkFeedbackThreadChangedEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Announces a resolved or reopened thread with the provider account that changed it. The event's sender says who; a
 * resolution the provider reports without a sender, such as one a GitLab sync reads, names the thread's resolver. A
 * reopening without a sender names nobody.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScmFeedbackThreadStateListener {
    private final ScmFeedbackThreadStateRepository rows;
    private final ApplicationEventPublisher events;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(ScmDomainEvent.ReviewThreadEvent event) {
        boolean resolved = event instanceof ScmDomainEvent.ReviewThreadResolved;
        Long sender = event.context().actorUserId();
        Long actor = sender == null ? null : rows.nativeId(sender).orElse(null);
        for (var thread : rows.find(event.thread().id())) {
            Long changedBy = actor != null ? actor : resolved ? thread.getResolvedBy() : null;
            events.publishEvent(new WorkFeedbackThreadChangedEvent(
                    thread.getWorkspaceId(), thread.getItemId(), thread.getThreadRef(), resolved, text(changedBy)));
        }
    }

    private static @Nullable String text(@Nullable Long id) {
        return id == null ? null : Long.toString(id);
    }
}
