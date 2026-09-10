package de.tum.cit.aet.hephaestus.workspace.spi;

import java.util.List;

/** Runtime-owned model readiness, without exposing catalog or credential internals to onboarding. */
public interface WorkspaceAiAvailability {
    List<Option> options(long workspaceId);

    record Option(MemberAiChoice choice, boolean practiceReviewsReady, boolean mentorReady) {}
}
