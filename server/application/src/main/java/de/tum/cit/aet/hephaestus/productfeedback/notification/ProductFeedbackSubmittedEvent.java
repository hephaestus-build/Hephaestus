package de.tum.cit.aet.hephaestus.productfeedback.notification;

import java.time.Instant;
import java.util.UUID;

public record ProductFeedbackSubmittedEvent(UUID feedbackId, Instant submittedAt) {}
