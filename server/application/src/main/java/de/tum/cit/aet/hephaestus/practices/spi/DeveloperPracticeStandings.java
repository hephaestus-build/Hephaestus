package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.List;

/** The developer's own practice profile, with the authority checks needed for conversational use. */
public interface DeveloperPracticeStandings {
    List<AssessmentPort.Standing> standings(long workspaceId, long accountId);
}
