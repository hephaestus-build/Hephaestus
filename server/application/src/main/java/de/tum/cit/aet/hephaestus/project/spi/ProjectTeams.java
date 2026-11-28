package de.tum.cit.aet.hephaestus.project.spi;

import java.util.UUID;

/** The receiving-team authority of ADR 0165: a team's work reaches only that team's current members. */
public interface ProjectTeams {
    /**
     * Whether the account is now a member of the team and of its workspace. A workspace role grants nothing: an owner or
     * an admin outside the team gets false. A team of another workspace, a removed team and an unknown id get false.
     */
    boolean isCurrentMember(long workspaceId, UUID teamId, long accountId);
}
