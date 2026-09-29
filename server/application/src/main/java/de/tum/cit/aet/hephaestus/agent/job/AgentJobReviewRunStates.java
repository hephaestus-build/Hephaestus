package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewOutcomeLookup.ReviewRunState;

/**
 * How a job status reads outside this module. A timed-out run and a cancelled one both did not finish, and no
 * surface renders the difference.
 */
final class AgentJobReviewRunStates {

    private AgentJobReviewRunStates() {}

    static ReviewRunState of(AgentJobStatus status) {
        return switch (status) {
            case QUEUED, RUNNING -> ReviewRunState.IN_PROGRESS;
            case COMPLETED -> ReviewRunState.COMPLETED;
            case FAILED, TIMED_OUT, CANCELLED -> ReviewRunState.FAILED;
        };
    }
}
