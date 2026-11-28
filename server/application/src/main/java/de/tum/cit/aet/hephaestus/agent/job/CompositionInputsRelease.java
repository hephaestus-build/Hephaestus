package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Releases what a review kept for its composition once a composition attempt ended: it stops a repair whose retries
 * are spent, resumes a pending one, or removes the inputs. It runs after the end commits and in its own transaction
 * (transactions.mdx § 4), and the publication registry delivers it again after a failure.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class CompositionInputsRelease {
    private final ReviewJobRepository jobs;
    private final ReviewCompositionContinuation continuation;

    @ApplicationModuleListener
    public void ended(CompositionAttemptEnded event) {
        jobs.findById(event.jobId())
                .filter(job -> job.getWorkspaceId() == event.workspaceId())
                .ifPresent(continuation::forget);
    }
}
