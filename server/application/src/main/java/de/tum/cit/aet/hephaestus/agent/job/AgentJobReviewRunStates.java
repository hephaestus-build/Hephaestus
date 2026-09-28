package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewOutcomeLookup.ReviewRunState;

/**
 * How a job status reads to a surface outside this module. A timed-out run and a cancelled one are both
 * "it did not finish" to a reader; keeping the distinction here would put a vocabulary on the wire that no
 * surface renders. One home, because the trace and the developer's own run list must not disagree about
 * what a cancelled run is.
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
