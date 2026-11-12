package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackReadbackObservedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class FeedbackRepairWake {
    private final ReviewRunKind reviewRuns;

    @ApplicationModuleListener
    public void observed(FeedbackReadbackObservedEvent event) {
        reviewRuns.resumeComposition(event.workspaceId(), event.reviewId());
    }
}
