package de.tum.cit.aet.hephaestus.productfeedback.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The survey owner selects eligible people; the notification adapter owns contact subscriptions and delivery. */
public interface SurveyEmailDelivery {
    boolean configured();

    List<Long> subscribedAccountIds(boolean research);

    boolean isSubscribed(long accountId, boolean research);

    void request(
            UUID surveyId, long accountId, Instant requestedAt, Instant expiresAt, boolean reminder, long generation);

    void requestSummary(UUID surveyId, Instant endedAt, Instant expiresAt);
}
