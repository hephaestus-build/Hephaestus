package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.UUID;

/** The mentor's decision that advice no longer applies. The owner's dismissal is separate. */
public record RetireAdvice(
        long workspaceId,
        long accountId,
        long practiceId,
        UUID reviewId,
        long seenVersion,
        AdviceRetireReason reason) {}
