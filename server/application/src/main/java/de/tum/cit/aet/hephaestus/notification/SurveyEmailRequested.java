package de.tum.cit.aet.hephaestus.notification;

import java.time.Instant;
import java.util.UUID;

public record SurveyEmailRequested(
        UUID surveyId, long accountId, Instant expiresAt, boolean reminder, long generation) {}
