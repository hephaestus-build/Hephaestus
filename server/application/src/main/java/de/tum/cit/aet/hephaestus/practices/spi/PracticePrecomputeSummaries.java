package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.List;

/**
 * What each practice's precompute script needs, without exposing agent-job persistence or model routing
 * to the practices module. A script declares its needs only when it runs, so they come from the newest
 * review that ran it.
 */
public interface PracticePrecomputeSummaries {
    /**
     * @return one summary per practice of the workspace that has a precompute script, by practice name
     */
    List<PracticePrecomputeSummaryDTO> latestCurrent(long workspaceId);
}
