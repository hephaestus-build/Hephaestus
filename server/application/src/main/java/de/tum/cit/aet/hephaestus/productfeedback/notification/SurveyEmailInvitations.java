package de.tum.cit.aet.hephaestus.productfeedback.notification;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SurveyEmailInvitations {
    Optional<Invitation> eligibleInvitation(UUID surveyId, long accountId, boolean reminder, long generation);

    void markAccepted(UUID surveyId, long accountId, Instant acceptedAt, boolean reminder, long generation);

    Optional<Summary> endedSummary(UUID surveyId);

    record Summary(Instant endedAt, long invited, long responded, long declined) {}

    record Invitation(boolean research, String workspaceSlug) {}
}
