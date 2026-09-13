package de.tum.cit.aet.hephaestus.notification;

import java.time.Instant;

public record ProductFeedbackDigestRequested(long accountId, Instant from, Instant until) {}
