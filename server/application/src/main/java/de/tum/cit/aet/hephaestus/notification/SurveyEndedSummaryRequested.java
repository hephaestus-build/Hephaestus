package de.tum.cit.aet.hephaestus.notification;

import java.time.Instant;
import java.util.UUID;

public record SurveyEndedSummaryRequested(UUID surveyId, long accountId, Instant endedAt, Instant expiresAt) {}
